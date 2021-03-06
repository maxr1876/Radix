/*
Copyright (c) 2018, Computer Science Department, Colorado State University
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

This software is provided by the copyright holders and contributors "as is" and
any express or implied warranties, including, but not limited to, the implied
warranties of merchantability and fitness for a particular purpose are
disclaimed. In no event shall the copyright holder or contributors be liable for
any direct, indirect, incidental, special, exemplary, or consequential damages
(including, but not limited to, procurement of substitute goods or services;
loss of use, data, or profits; or business interruption) however caused and on
any theory of liability, whether in contract, strict liability, or tort
(including negligence or otherwise) arising in any way out of the use of this
software, even if advised of the possibility of such damage.
*/

/**
 * @author Max Roselius: mroseliu@rams.colostate.edu
 * This class runs as its own thread on each node in a Galileo cluster. It listens for a particular command
 * to begin ingesting data from a particular location. The command to issue is : echo "<filePathToIngest>" | nc <host-name> 42070
 * This tells the host machine where to begin searching for data to ingest, 42070 is the port number.*/
package galileo.dataset;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.logging.Logger;
import org.xerial.snappy.Snappy;
import galileo.bmp.HashGridException;
import galileo.client.EventPublisher;
import galileo.comm.NonBlockStorageRequest;
import galileo.config.SystemConfig;
import galileo.dht.NodeInfo;
import galileo.dht.PartitionException;
import galileo.dht.SpatialHierarchyPartitioner;
import galileo.dht.StorageNode;
import galileo.dht.hash.HashException;
import galileo.fs.GeospatialFileSystem;
import galileo.net.ClientMessageRouter;
import galileo.util.CustomBufferedReader;
import web.Sampler;
import web.SamplerResponse;

public class DataIngestor extends Thread{
	public String [] hosts;
	public int index = 0;
	public BlockingQueue<String> queue = new PriorityBlockingQueue<>();
	private StorageNode sn;
	private ChunkProcessor [] threadPool;
	public ArrayList<Long> stampTimes = new ArrayList<>();
	public ArrayList<Long> avgStamps = new ArrayList<>();
	private int portNum = 42070;
	public volatile boolean shouldStop = false;
	private ServerSocketChannel serverChannel;
	private Selector selector;
	public volatile long sumStampTimes = 0, numStamps = 0;
	private static Logger logger = Logger.getLogger("galileo");
	public DataIngestor(StorageNode sn) throws IOException{
		this.sn = sn;
		this.selector = initSelector();
		String nodeFile = SystemConfig.getNetworkConfDir() + File.separator + "hostnames";
		this.hosts = new String(Files.readAllBytes(Paths.get(nodeFile))).split(System.lineSeparator());
		this.threadPool = new ChunkProcessor[10];
		for (int i = 0; i < threadPool.length; i++)  
			threadPool[i] = new ChunkProcessor(this, this.sn);
			
	}
	public void shutdown() {
		this.shouldStop = true;
		try {
			serverChannel.close();
			selector.close();
		} catch (IOException e) {
		}
	}
	@Override
	public void run(){
		while (!shouldStop) {
		      try {
		        // Wait for an event one of the registered channels
		        this.selector.select();
		      
	        	// Iterate over the set of keys for which events are available
		        Iterator <SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();
		        while (selectedKeys.hasNext()) {
		          SelectionKey key = (SelectionKey) selectedKeys.next();
		          selectedKeys.remove();

		          if (!key.isValid()) {
		            continue;
		          }
		          // Check what event is available and deal with it
		          if (key.isAcceptable()) {
		            this.accept(key);
		          }
		        
		          if (key.isReadable())
		        	  this.read(key);
		          			          
		          //Should not have to worry about writable keys in server, only threadPool should see those
		        }
		        
		        
		      } catch (Exception e) {
		        logger.severe("Error trying to read incoming data. " + e);
		        logger.severe("Stack trace: " + Arrays.toString(e.getStackTrace()));
		      }
		    }
		try {
			serverChannel.close();
		} catch (IOException e) {
			logger.severe("Error closing listener server channel " + e);
		}
		return;
		
	}
	
	public void kill() {
		this.shouldStop = true;
		try {
			this.selector.close();
		} catch (IOException e) {
			logger.info("Failed to kill listener due to " + e);
		}
	}
	private Selector initSelector() throws IOException {
	    // Create a new selector
	    Selector socketSelector = SelectorProvider.provider().openSelector();

	    // Create a new non-blocking server socket channel
	    this.serverChannel = ServerSocketChannel.open();
	    serverChannel.configureBlocking(false);

	    // Bind the server socket to the specified address and port
	    InetSocketAddress isa = new InetSocketAddress(InetAddress.getLocalHost(), this.portNum);
	    serverChannel.socket().bind(isa);

	    // Register the server socket channel, indicating an interest in 
	    // accepting new connections
	    serverChannel.register(socketSelector, SelectionKey.OP_ACCEPT);

	    return socketSelector;
	  }
	
	private void accept(SelectionKey key) throws IOException {
	    // For an accept to be pending the channel must be a server socket channel.
	    ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();

	    // Accept the connection and make it non-blocking
	    SocketChannel socketChannel = serverSocketChannel.accept();
	    socketChannel.configureBlocking(false);

	    // Register the new SocketChannel with our Selector, indicating
	    // we'd like to be notified when there's data waiting to be read
	    socketChannel.register(this.selector, SelectionKey.OP_READ);
	  }
	
	/**
	 * This method reads the filepath from a selection key that has been sent to the listening port (42070 by default).
	 * @param key the selection key to read from. This key will contain a filepath 
	 * @return a string representing the filepath to read for data ingestion.
	 * */
	private String readKey(SelectionKey key) {
		String filePath = "";
		try {
			SocketChannel channel = (SocketChannel) key.channel();
	        ByteBuffer buffer = ByteBuffer.allocate(1024 * 100); //Read 100 KB at a time
	        buffer.clear(); //clear for safety
	        int numRead = -1;
	        synchronized(channel) {
		         numRead = channel.read(buffer);
		         
		         //need to read in loop
		         while (numRead > -1) {
		        	 byte[] data = new byte[numRead];
		        	 System.arraycopy(buffer.array(), 0, data, 0, numRead);
		        	 filePath += new String(data);
		        	 buffer.clear(); //important to clear buffer to eliminate leftover data
		        	 numRead = channel.read(buffer);
		         }
		         channel.close();
		         key.cancel();
		         //Strip off any newline characters
		         filePath = filePath.replaceAll("\n", "");
	        }
		}catch (IOException e) {
			logger.severe("Error reading file path");
		}
		return filePath;
	}
	/**
	 * This method determines the filepath sent from an external node to read data from. Data is read in 250 MB chunks from disk,
	 * which are then split into smaller messages to be "stamped" and forwarded to the appropriate node in the Radix cluster.
	 * @param key the selection key to read*/
	private void read(SelectionKey key) throws IOException, HashException, PartitionException {
		logger.info("Beginnning to read ingest file. Time: " + new Date(System.currentTimeMillis()));
		for (ChunkProcessor cp : threadPool)
			cp.start();
		String filePath = readKey(key);
		boolean fileRead = false;
		String lineSep = System.lineSeparator();
		File f = new File(filePath);
		
		long offSet = 250000*1024;//read 250MB at a time
		long start = 0;
		long linesCounted = 0;
		long lineCount = 0;
		long numMsgs = 0;
		@SuppressWarnings("resource")// inChannel is closed, which in turn closes RandomAccessFile, safely ignore warning
		FileChannel  inChannel = new RandomAccessFile(filePath, "r").getChannel();
		StringBuilder chunk = new StringBuilder();
		if (f.exists()) 
			while (!fileRead) {
								
				try {
					
					if (start + offSet > inChannel.size()) {
						offSet = inChannel.size() - start;
						fileRead = true;
					}
					MappedByteBuffer mmb = inChannel.map(FileChannel.MapMode.READ_ONLY, start, offSet);//inChannel.size()
					byte[] buffer = new byte[(int)offSet];
				    mmb.get(buffer);
					start += offSet;
					CustomBufferedReader in = new CustomBufferedReader(new InputStreamReader(new ByteArrayInputStream(buffer)));
					
					for (String line = in.readLine(); line != null; line = in.readLine()) {
						chunk.append(line);
						if (line.endsWith(lineSep)) {
							lineCount++;
							linesCounted++;
						}
						if (lineCount == 400) {//let's test this shall we?
							queue.add(chunk.toString());
							numMsgs++;
							chunk = new StringBuilder();
							lineCount = 0;
						}
					}
					logger.info("Processed 250 MB of file and added " + numMsgs + " messages to be stamped");
					numMsgs= 0;
					in.close();
				}catch(Exception e) {
					logger.info("DataIngestor error: " + e);
				}
			}
		else
			logger.warning("DataIngestor received invalid file path: " + filePath);

		while(queue.size() > 0) {

		}
		inChannel.close();
		logger.info("All messages stamped and sent\n Sent a total of " + linesCounted + " lines.");
		logger.info("Average compress/stamp/send time: " + (double)sumStampTimes/(double)numStamps);
		for (ChunkProcessor cp : threadPool)
			cp.kill();
	}
	
	
	 static class ChunkProcessor extends Thread {
	        private final DataIngestor master;
	        private final StorageNode sn;
	        private ClientMessageRouter messageRouter;
	        private volatile boolean alive;
	        public ChunkProcessor(DataIngestor master, StorageNode sn) throws IOException {
	            this.master = master;
	            this.sn = sn;
	            this.messageRouter = new ClientMessageRouter();
	        }
	        private void sendMessage(byte[] message, NodeInfo dest, boolean checkAll) throws IOException {
	        	NonBlockStorageRequest request = new NonBlockStorageRequest(message, "roots");
	        	request.setCheckAll(checkAll);
	        	messageRouter.sendMessage(dest, EventPublisher.wrapEvent(request));
	   		 
	   	 	}
	        
	        public void kill() {
	        	alive = false;
	        }
	        public void run() {
	        	alive = true;
	        	Sampler sampler = new Sampler(((SpatialHierarchyPartitioner)((GeospatialFileSystem)this.sn.getFS("roots")).getPartitioner()));
	        	while(alive) {
					try {
						String data = master.queue.take();
						long start = System.currentTimeMillis();
						byte[] compressed = Snappy.compress(data);
						SamplerResponse response = sampler.sample(this.sn.getGlobalGrid(), data);
						HashMap<NodeInfo, Integer> dests = response.getNodeMap();
						if (dests.keySet().size() == 1) {//all data belongs to one node
							Map.Entry<NodeInfo,Integer> entry = dests.entrySet().iterator().next();
							NodeInfo dest = entry.getKey();
							//no good
							if (dest != null && dest.toString().split(":")[0].contentEquals(this.sn.getHostName()))//data belongs to this node, no need for network xfer
								this.sn.handleLocalNonBlockStorageRequest(new NonBlockStorageRequest(compressed, "roots"));
							else if (dest != null)
								sendMessage(compressed, dest, response.checkAll());
							else
								logger.severe("Identified null as destination");
						}
						else {
							NodeInfo finalDest = null;
							int count = 0;
							for (NodeInfo node : dests.keySet()){
								if (dests.get(node) > count){
									finalDest = node;
									count = dests.get(node);
								}
							}//no good
							if (finalDest != null && finalDest.toString().split(":")[0].contentEquals(this.sn.getHostName()))//data belongs to this node, no need for network xfer
								this.sn.handleLocalNonBlockStorageRequest(new NonBlockStorageRequest(compressed, "roots"));
							else if (finalDest!=null)
								sendMessage(compressed, finalDest, response.checkAll());
						}
						master.sumStampTimes += (System.currentTimeMillis()-start);
						master.numStamps ++;
					} catch (InterruptedException | HashGridException | IOException | NullPointerException e) {
						logger.severe("Error trying to stamp. " + Arrays.toString(e.getStackTrace()));
					}
		        }
	        }
	    }

	
}

