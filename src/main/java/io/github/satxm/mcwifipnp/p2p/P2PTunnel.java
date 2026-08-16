package io.github.satxm.mcwifipnp.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import computer.iroh.BiStream;
import computer.iroh.RecvStream;
import computer.iroh.SendStream;

/**
 * Bidirectional relay between a TCP socket and an iroh bidirectional stream.
 *
 * <p>Host side: iroh stream &lt;-&gt; 127.0.0.1:&lt;server port&gt; (the game server
 * socket). Member side: local TCP client &lt;-&gt; iroh stream. Two threads pump
 * bytes in each direction; when either side closes, both are closed.
 */
public final class P2PTunnel implements Runnable {

	private static final int BUFFER_SIZE = 32 * 1024;

	private final Socket socket;
	private final BiStream stream;
	private final String name;

	public P2PTunnel(Socket socket, BiStream stream, String name) {
		this.socket = socket;
		this.stream = stream;
		this.name = name;
	}

	/** Run the relay until one direction closes, then tear down both. */
	@Override
	public void run() {
		final SendStream send = this.stream.send();
		final RecvStream recv = this.stream.recv();

		Thread tcpToIroh = new Thread(new Runnable() {
			@Override
			public void run() {
				pumpTcpToIroh(send);
			}
		}, this.name + "-tcp2iroh");
		Thread irohToTcp = new Thread(new Runnable() {
			@Override
			public void run() {
				pumpIrohToTcp(recv);
			}
		}, this.name + "-iroh2tcp");

		tcpToIroh.start();
		irohToTcp.start();

		try {
			tcpToIroh.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			irohToTcp.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		close();
	}

	private void pumpTcpToIroh(SendStream send) {
		byte[] buffer = new byte[BUFFER_SIZE];
		try {
			InputStream in = this.socket.getInputStream();
			int read;
			while ((read = in.read(buffer)) != -1) {
				KtBridge.writeAll(send, copyOf(buffer, read));
			}
			KtBridge.finish(send);
		} catch (IOException e) {
			// connection reset etc: treat as EOF
		} catch (Throwable t) {
			// iroh write failure: tear down
		}
		close();
	}

	private void pumpIrohToTcp(RecvStream recv) {
		byte[] buffer = new byte[BUFFER_SIZE];
		try {
			OutputStream out = this.socket.getOutputStream();
			byte[] chunk;
			while ((chunk = KtBridge.read(recv, buffer.length)) != null && chunk.length > 0) {
				out.write(chunk);
				out.flush();
			}
		} catch (IOException e) {
			// socket closed: treat as EOF
		} catch (Throwable t) {
			// iroh read failure: tear down
		}
		close();
	}

	private void close() {
		try {
			this.socket.close();
		} catch (IOException e) {
			// ignore
		}
		try {
			this.stream.close();
		} catch (Throwable t) {
			// ignore
		}
	}

	private static byte[] copyOf(byte[] src, int length) {
		byte[] copy = new byte[length];
		System.arraycopy(src, 0, copy, 0, length);
		return copy;
	}

	@Override
	public String toString() {
		return "P2PTunnel{" + this.name + "}";
	}
}
