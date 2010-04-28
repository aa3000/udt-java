/*********************************************************************************
 * Copyright (c) 2010 Forschungszentrum Juelich GmbH 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * (1) Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the disclaimer at the end. Redistributions in
 * binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.
 * 
 * (2) Neither the name of Forschungszentrum Juelich GmbH nor the names of its 
 * contributors may be used to endorse or promote products derived from this 
 * software without specific prior written permission.
 * 
 * DISCLAIMER
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *********************************************************************************/

package udt;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import udt.packets.Acknowledgement;
import udt.packets.Acknowledgment2;
import udt.packets.DataPacket;
import udt.packets.KeepAlive;
import udt.packets.NegativeAcknowledgement;
import udt.sender.SenderLossList;
import udt.sender.SenderLossListEntry;
import udt.util.UDTStatistics;
import udt.util.UDTThreadFactory;
import udt.util.Util;


/**
 * sender part of a UDT entity
 * 
 * @see UDTReceiver
 */
public class UDTSender {

	private static final Logger logger=Logger.getLogger(UDTClient.class.getName());

	private final UDPEndPoint endpoint;

	private final UDTSession session;

	private final UDTStatistics statistics;

	//sendLossList store the sequence numbers of lost packets
	//feed back by the receiver through NAK pakets
	private final SenderLossList senderLossList;
	//sendBuffer stores the sent data packets and their sequence numbers
	private final Map<Long,DataPacket>sendBuffer;
	//sendQueue contains the packets to send
	private final BlockingQueue<DataPacket>sendQueue;
	//thread reading packets from send queue and sending them
	private Thread senderThread;

	//protects against races when reading/writing to the sendBuffer
	private final Object sendLock=new Object();

	//number of unacknowledged data packets
	private final AtomicInteger unacknowledged=new AtomicInteger(0);

	//for generating data packet sequence numbers
	private long nextSequenceNumber=0;

	//the largest data packet sequence number that has actually been sent out
	private volatile long largestSentSequenceNumber=-1;

	//last acknowledge number, initialised to the initial sequence number
	private long lastAckSequenceNumber;

	private volatile boolean started=false;

	private volatile boolean stopped=false;

	private final CountDownLatch startLatch=new CountDownLatch(1);

	private final AtomicReference<CountDownLatch> waitForAckLatch=new AtomicReference<CountDownLatch>();

	private final AtomicReference<CountDownLatch> waitForSeqAckLatch=new AtomicReference<CountDownLatch>();

	public UDTSender(UDTSession session,UDPEndPoint endpoint){
		if(!session.isReady())throw new IllegalStateException("UDTSession is not ready.");
		this.endpoint= endpoint;
		this.session=session;
		statistics=session.getStatistics();
		senderLossList=new SenderLossList();
		sendBuffer=new ConcurrentHashMap<Long, DataPacket>(session.getFlowWindowSize(),0.75f,2); 
		sendQueue = new LinkedBlockingQueue<DataPacket>(1000);  
		lastAckSequenceNumber=session.getInitialSequenceNumber();
		waitForAckLatch.set(new CountDownLatch(1));
		waitForSeqAckLatch.set(new CountDownLatch(1));
		doStart();
	}

	/**
	 * start the sender thread
	 */
	public void start(){
		logger.info("Starting sender for "+session);
		startLatch.countDown();
		started=true;
	}

	//starts the sender algorithm
	private void doStart(){
		Runnable r=new Runnable(){
			public void run(){
				try{
					//wait until explicitely started
					startLatch.await();
					while(!stopped){
						senderAlgorithm();
					}
				}catch(InterruptedException ie){
					ie.printStackTrace();
				}
				catch(IOException ex){
					ex.printStackTrace();
					logger.log(Level.SEVERE,"",ex);
				}
				logger.info("STOPPING SENDER for "+session);
			}
		};
		senderThread=UDTThreadFactory.get().newThread(r);
		senderThread.start();
	}


	/** 
	 * sends the given data packet, storing the relevant information
	 * 
	 * @param data
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void send(DataPacket p)throws IOException{
		synchronized(sendLock){
			endpoint.doSend(p);
			sendBuffer.put(p.getPacketSequenceNumber(), p);
			unacknowledged.incrementAndGet();
		}
		statistics.incNumberOfSentDataPackets();
	}

	/**
	 * writes a data packet into the sendQueue, waiting at most for the specified time
	 * if this is not possible due to a full send queue
	 * 
	 * @return <code>true</code>if the packet was added, <code>false</code> if the
	 * packet could not be added because the queue was full
	 * @param p
	 * @param timeout
	 * @param units
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	protected boolean sendUdtPacket(DataPacket p, int timeout, TimeUnit units)throws IOException,InterruptedException{
		if(!started)start();
		return sendQueue.offer(p,timeout,units);
	}

	//receive a packet from server from the peer
	protected void receive(UDTPacket p)throws IOException{
		if (p instanceof Acknowledgement) {
			Acknowledgement acknowledgement=(Acknowledgement)p;
			onAcknowledge(acknowledgement);
		}
		else if (p instanceof NegativeAcknowledgement) {
			NegativeAcknowledgement nak=(NegativeAcknowledgement)p;
			onNAKPacketReceived(nak);
		}
		else if (p instanceof KeepAlive) {
			session.getSocket().getReceiver().resetEXPCount();
		}
	}

	protected void onAcknowledge(Acknowledgement acknowledgement)throws IOException{
		waitForAckLatch.get().countDown();
		waitForSeqAckLatch.get().countDown();
		
		CongestionControl cc=session.getCongestionControl();
		long rtt=acknowledgement.getRoundTripTime();
		if(rtt>0){
			long rttVar=acknowledgement.getRoundTripTimeVar();
			cc.setRTT(rtt,rttVar);
			statistics.setRTT(rtt, rttVar);
		}
		long rate=acknowledgement.getPacketReceiveRate();
		if(rate>0){
			long linkCapacity=acknowledgement.getEstimatedLinkCapacity();
			cc.updatePacketArrivalRate(rate, linkCapacity);
			statistics.setPacketArrivalRate(cc.getPacketArrivalRate(), cc.getEstimatedLinkCapacity());
		}

		long ackNumber=acknowledgement.getAckNumber();
		cc.onACK(ackNumber);
		//need to remove all sequence numbers up the ack number from the sendBuffer
		boolean removed=false;
		for(long s=lastAckSequenceNumber;s<ackNumber;s++){
			synchronized (sendLock) {
				removed=sendBuffer.remove(s)!=null;
			}
			if(removed){
				unacknowledged.decrementAndGet();
			}
		}
		lastAckSequenceNumber=Math.max(lastAckSequenceNumber, ackNumber);		
		//send ACK2 packet to the receiver
		sendAck2(ackNumber);
		statistics.incNumberOfACKReceived();
		statistics.storeParameters();
	}

	/**
	 * procedure when a NAK is received (spec. p 14)
	 * @param nak
	 */
	protected void onNAKPacketReceived(NegativeAcknowledgement nak){
		waitForAckLatch.get().countDown();
		
		for(Integer i: nak.getDecodedLossInfo()){
			senderLossList.insert(new SenderLossListEntry(i));
		}
		session.getCongestionControl().onNAK(nak.getDecodedLossInfo());
		session.getSocket().getReceiver().resetEXPTimer();
		statistics.incNumberOfNAKReceived();
		statistics.storeParameters();

		if(logger.isLoggable(Level.FINER)){
			logger.finer("NAK for "+nak.getDecodedLossInfo().size()+" packets lost, " 
					+"set send period to "+session.getCongestionControl().getSendInterval());
		}

		return;
	}

	//send single keep alive packet -> move to socket!
	protected void sendKeepAlive()throws Exception{
		KeepAlive keepAlive = new KeepAlive();
		//TODO
		keepAlive.setSession(session);
		endpoint.doSend(keepAlive);
	}

	protected void sendAck2(long ackSequenceNumber)throws IOException{
		Acknowledgment2 ackOfAckPkt = new Acknowledgment2();
		ackOfAckPkt.setAckSequenceNumber(ackSequenceNumber);
		ackOfAckPkt.setSession(session);
		ackOfAckPkt.setDestinationID(session.getDestination().getSocketID());
		endpoint.doSend(ackOfAckPkt);
	}

	/**
	 * sender algorithm
	 */
	public void senderAlgorithm()throws InterruptedException, IOException{
		long iterationStart=Util.getCurrentTime();
		
		//if the sender's loss list is not empty 
		SenderLossListEntry entry=senderLossList.getFirstEntry();
		if (entry!=null) {
			handleResubmit(entry);
		}
		
		else
		{
			//if the number of unacknowledged data packets does not exceed the congestion 
			//and the flow window sizes, pack a new packet
			int unAcknowledged=unacknowledged.get();
			
			if(unAcknowledged<session.getCongestionControl().getCongestionWindowSize()
					&& unAcknowledged<session.getFlowWindowSize()){
				//check for application data
				DataPacket dp=sendQueue.poll();//10*Util.getSYNTime(),TimeUnit.MICROSECONDS);
				if(dp!=null){
					send(dp);
					largestSentSequenceNumber=dp.getPacketSequenceNumber();
				}
				else {
					Thread.yield();
					return;
				}
			}else{
				//congestion window full, should we *really* wait for an ack?!
				if(unAcknowledged>=session.getCongestionControl().getCongestionWindowSize()){
					statistics.incNumberOfCCWindowExceededEvents();
				}
				Thread.sleep(1);
				//waitForAck();
				return;
			}
		}
		
		//wait
		

		double snd=session.getCongestionControl().getSendInterval();
		long passed=Util.getCurrentTime()-iterationStart;
		int x=0;
		while(snd-passed>0){
			if(x++==0)statistics.incNumberOfCCSlowDownEvents();
			//we cannot wait with microsecond precision
			if(snd-passed>750)Thread.sleep(1);
			else if((snd-passed)/snd > 0.9){
				return;
			}
			passed=Util.getCurrentTime()-iterationStart;
		}

	}

	/**
	 * re-submits an entry from the sender loss list
	 * @param entry
	 */
	protected void handleResubmit(SenderLossListEntry entry){
		long seqNumber = entry.getSequenceNumber();
		//TODO
		//if the current seqNumber is 16n,check the timeOut in the 
		//loss list and send a message drop request.
		//if((seqNumber%16)==0){
		//sendLossList.checkTimeOut(timeToLive);
		//}
		try {
			//retransmit the packet and remove it from  the list
			DataPacket pktToRetransmit = sendBuffer.get(seqNumber);
			if(pktToRetransmit!=null){
				endpoint.doSend(pktToRetransmit);
				statistics.incNumberOfRetransmittedDataPackets();
			}		
		}catch (Exception e) {
			logger.log(Level.WARNING,"",e);
		}
	}
	
	/**
	 * for processing EXP event (see spec. p 13)
	 */
	protected void putUnacknowledgedPacketsIntoLossList(){
		synchronized (sendLock) {
			for(Long l: sendBuffer.keySet()){
				senderLossList.insert(new SenderLossListEntry(l));
			}
		}
	}

	/**
	 * the next sequence number for data packets.
	 * The initial sequence number is "0"
	 */
	public long getNextSequenceNumber(){
		nextSequenceNumber++;
		return nextSequenceNumber;
	}

	public long getCurrentSequenceNumber(){
		return nextSequenceNumber;
	}

	/**
	 * returns the largest sequence number sent so far
	 */
	public long getLargestSentSequenceNumber(){
		return largestSentSequenceNumber;
	}
	/**
	 * returns the last Ack. sequence number 
	 */
	public long getLastAckSequenceNumber(){
		return lastAckSequenceNumber;
	}

	boolean haveAcknowledgementFor(long sequenceNumber){
		return sequenceNumber<=lastAckSequenceNumber;
	}

	boolean isSentOut(long sequenceNumber){
		return largestSentSequenceNumber>=sequenceNumber;

	}

	boolean haveLostPackets(){
		return !senderLossList.isEmpty();
	}

	/**
	 * wait until the given sequence number has been acknowledged
	 * 
	 * @throws InterruptedException
	 */
	public synchronized void waitForAck(long sequenceNumber)throws InterruptedException{
		while(!session.isShutdown() && !haveAcknowledgementFor(sequenceNumber)){
			waitForSeqAckLatch.set(new CountDownLatch(1));
			waitForSeqAckLatch.get().await(10, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * wait for the next acknowledge
	 * @throws InterruptedException
	 */
	public synchronized void waitForAck()throws InterruptedException{
		waitForAckLatch.set(new CountDownLatch(1));
		waitForAckLatch.get().await(1000, TimeUnit.MILLISECONDS);
	}


	public void stop(){
		stopped=true;
	}
}
