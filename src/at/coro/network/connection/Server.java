package at.coro.network.connection;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.security.KeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import at.coro.crypto.ADEC;
import at.coro.run.Main;

public class Server implements Runnable {

	protected Socket clientSocket;
	protected ObjectOutputStream oos;
	protected ObjectInputStream ois;

	protected ADEC cryptoEngine;
	protected PublicKey clientPublicKey;

	protected final String serverAnnounce = "[SERVER]";
	protected final String serverInfo = "SRV_INFO>>";
	protected String username = "user_" + Math.round(Math.random() * 1000);
	protected final int uuid = (int) Math.round(Math.random() * 10000000);
	protected String ipAddress = "";
	protected String password = "";

	public Server(Socket socket, String password) throws IOException {
		System.out.println("--------------------\nNew incoming client connection!\nAddress is: "
				+ socket.getRemoteSocketAddress());
		System.out.println("Username is: " + this.username);
		this.ipAddress = socket.getRemoteSocketAddress().toString();
		this.password = password;
		this.clientSocket = socket;
		this.oos = new ObjectOutputStream(this.clientSocket.getOutputStream());
		this.ois = new ObjectInputStream(this.clientSocket.getInputStream());
	}

	public void disconnect() throws IOException {
		// sendEncryptedMessage("");
		this.clientSocket.close();
		return;
	}

	public String getUsername() {
		return this.username;
	}

	public void sendMessage(Object message) throws IOException {
		this.oos.writeObject(message);
	}

	public void sendEncryptedMessage(Object message) throws InvalidKeyException, IllegalBlockSizeException,
			BadPaddingException, NoSuchAlgorithmException, NoSuchPaddingException, IOException {
		this.oos.writeObject(cryptoEngine.encryptString(clientPublicKey, message.toString()));
	}

	public Object listen() throws ClassNotFoundException, IOException {
		return listen(0);
	}

	public Object listen(int timeout) throws ClassNotFoundException, IOException {
		this.clientSocket.setSoTimeout(timeout);
		Object returnObj = ois.readObject();
		this.clientSocket.setSoTimeout(0);
		return returnObj;
	}

	@Override
	public void run() {
		Main mainThread = new Main();
		try {
			System.out.println("Generating keys...");
			cryptoEngine = new ADEC(2048);
			System.out.println("Done!");
			// Exchanging keys
			System.out.println("Waiting for Public Key...");
			clientPublicKey = (PublicKey) listen(5000);
			System.out.println("Sending Public Key...");
			sendMessage(cryptoEngine.publicKey());
			//
			if (!this.password.isEmpty()) {
				System.out.println("Requesting password...");
				if (!this.password.equals(cryptoEngine.decryptString((byte[]) listen(10000)))) {
					throw new Exception("WRONG_PASSWORD");
				}
			}
			System.out.println("Success!\n--------------------");
			sendEncryptedMessage(this.serverInfo + "CORRECT_PASSWORD");
			mainThread.broadcastMessage(this.serverAnnounce + ": A NEW USER JOINED THE FUN: " + this.username);
		} catch (IOException ioex) {
			System.err.println("IO Error occurred! Maybe there was a timeout!");
			return;
			// System.err.println();
		} catch (KeyException | NoSuchAlgorithmException | NoSuchProviderException | ClassNotFoundException
				| IllegalBlockSizeException | BadPaddingException | NoSuchPaddingException e) {
			System.err.println("An Error Occurred!\nCause: " + e.getMessage());
			return;
		} catch (Exception ex) {
			if (ex.getMessage().equals("WRONG_PASSWORD")) {
				System.err.println("Wrong Password!");
				try {
					sendEncryptedMessage(this.serverInfo + "WRONG_PASSWORD");
				} catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException
						| NoSuchAlgorithmException | NoSuchPaddingException | IOException e) {
					e.printStackTrace();
				}
				return;
			}
		}

		do {

			String clientMessage = null;
			try {

				clientMessage = cryptoEngine.decryptString((byte[]) listen());
				System.out.println(username + "(" + this.clientSocket.getRemoteSocketAddress() + "): " + clientMessage);
				if (clientMessage.toUpperCase().startsWith("/HELP")) {
					String commands = "/count : Returns count of users online\n/chusr [username] : Changes your current username to the specified one\n/users : Returns the usernames currently logged in";
					sendEncryptedMessage(this.serverAnnounce + ": COMMAND LIST:\n" + commands);
				} else if (clientMessage.toUpperCase().startsWith("/BRD")
						&& !clientMessage.substring(clientMessage.indexOf(" ") + 1, clientMessage.length()).isEmpty()) {
					mainThread.broadcastMessage("<" + this.username + ">: "
							+ clientMessage.substring(clientMessage.indexOf(" ") + 1, clientMessage.length()));
				} else if (clientMessage.toUpperCase().startsWith("/CHUSR")) {
					String user = clientMessage.split(" ")[1];
					if (!mainThread.userExists(user) && !user.toUpperCase().contains(this.serverAnnounce.toUpperCase())
							&& !user.equalsIgnoreCase("ALL")) {
						mainThread.broadcastMessage(
								this.serverAnnounce + ": " + this.username + " IS NOW KNOWN UNDER THE NAME " + user);
						this.username = user;
					} else {
						sendEncryptedMessage(
								this.serverAnnounce + ": USERNAME " + user + " IS ALREADY TAKEN, CHOOSE ANOTHER ONE!");
					}
				} else if (clientMessage.toUpperCase().startsWith("/COUNT")) {
					sendEncryptedMessage(this.serverAnnounce + ": USERS ONLINE: " + mainThread.userCount());
				} else if (clientMessage.toUpperCase().startsWith("/SAY")) {
					String recipient = clientMessage.split(" ")[1];
					String messageBody = "<" + this.username + ">: " + clientMessage.substring(
							clientMessage.indexOf(recipient) + recipient.length() + 1, clientMessage.length());
					mainThread.directMessage(recipient, messageBody);
				} else if (clientMessage.toUpperCase().startsWith("/USERS")) {
					String[] onlineUsers = mainThread.getUsers();
					String userList = "| ";
					for (int i = 0; i < onlineUsers.length; i++) {
						userList += onlineUsers[i] + " | ";
					}
					sendEncryptedMessage(this.serverAnnounce + ": USERS: " + userList);
				}

			} catch (ClassNotFoundException | IOException | InvalidKeyException | NoSuchAlgorithmException
					| NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException e) {
				// System.err.println("An Error Occurred!\nCause: " +
				// e.getMessage());
				break;
			}

		} while (!this.clientSocket.isClosed());
		// Cleanup
		// System.out.println("CLEANING");
		try {
			this.ois.close();
			this.oos.close();
			this.clientSocket.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.err.println("Something went wrong while cleaning up! We're still fine though!");
		}
		System.out.println("User " + this.username + " Disconnected.");
		try {
			mainThread.broadcastMessage("User " + this.username + " says Goodbye!");
		} catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException | NoSuchAlgorithmException
				| NoSuchPaddingException | IOException e) {
			// System.out.println("FATAL ERROR:");
			// e.printStackTrace();
		}
		return;

	}
}
