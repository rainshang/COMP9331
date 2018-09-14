import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Ethan on 20/4/17.
 */

public class Client {

    private static boolean isRunning;
    private static ExecutorService executorService;

    private static Socket toServerSocket;
    private static BufferedReader fromServer;
    private static PrintWriter toServer;

    private static ServerSocket p2pSocket;//receive p2p request
    private static List<P2PThread> p2pThreads;
    private static String myName;

    /**
     * the arguments should be server_IP (String), server_port (int)
     *
     * @param args
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new RuntimeException("Arguments error! Needs server_IP, server_port");
        }
        try {
            InetAddress inetAddress = InetAddress.getByName(args[0]);
            int serverPort = Integer.parseInt(args[1]);
            isRunning = true;
            executorService = Executors.newCachedThreadPool();

            toServerSocket = new Socket(inetAddress, serverPort);
            fromServer = new BufferedReader(new InputStreamReader(toServerSocket.getInputStream()));
            toServer = new PrintWriter(toServerSocket.getOutputStream(), true);

            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    while (isRunning) {
                        try {
                            printDataFromServer();
                        } catch (IOException e) {
                        }
                    }
                }
            });

            p2pSocket = new ServerSocket(toServerSocket.getLocalPort() + 1);//avoid conflict with toServerSocket to server on localhost
            p2pThreads = new ArrayList<>();
            myName = "";
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    while (isRunning) {
                        try {
                            Socket clientSocket = p2pSocket.accept();
                            executorService.execute(new P2PThread(clientSocket));
                        } catch (IOException e) {
                        }
                    }
                }
            });

            loop:
            while (isRunning) {
                Scanner in = new Scanner(System.in);
                String command = in.nextLine();
                if (!command.isEmpty()) {
                    String[] params = command.trim().split("\\s+");
                    if ("startprivate".equals(params[0])) {
                        if (params.length == 2) {
                            synchronized (p2pThreads) {
                                for (P2PThread p2pThread : p2pThreads) {
                                    if (p2pThread.isP2PConnected(params[1])) {
                                        System.out.println(String.format("You don not need to do this. There is already an existing P2P connection between you and %1$s.", params[1]));
                                        continue loop;
                                    }
                                }
                                toServer.println(command);
                            }
                        } else {
                            System.out.println("Wrong format! This command should be like 'startprivate <user>'");
                        }
                    } else if ("private".equals(params[0])) {
                        if (params.length >= 3) {
                            synchronized (myName) {
                                if (!params[1].equals(myName)) {
                                    synchronized (p2pThreads) {
                                        for (P2PThread p2pThread : p2pThreads) {
                                            if (p2pThread.isP2PConnected(params[1])) {
                                                p2pThread.sendMsg(params[1], command.substring(params[0].length() + params[1].length() + 2));
                                                continue loop;
                                            }
                                        }
                                        System.out.println(String.format("There is no P2P connection between you and %1$s. Please execute 'startprivate %2$s' first.", params[1], params[1]));
                                    }
                                } else {
                                    System.out.println("You cannot send a private message to yourself.");
                                }
                            }
                        } else {
                            System.out.println("Wrong format! This command should be like 'private <user> <message>'");
                        }
                    } else if ("stopprivate".equals(params[0])) {
                        if (params.length == 2) {
                            synchronized (myName) {
                                if (!params[1].equals(myName)) {
                                    synchronized (p2pThreads) {
                                        for (P2PThread p2pThread : p2pThreads) {
                                            if (p2pThread.isP2PConnected(params[1])) {
                                                p2pThread.stop();
                                                p2pThreads.remove(p2pThread);
                                                continue loop;
                                            }
                                        }
                                        System.out.println(String.format("There is no connection between you and %1$s. ", params[1]));
                                    }
                                } else {
                                    System.out.println("These cannot be a P2P connection to yourself to stop.");
                                }
                            }
                        } else {
                            System.out.println("Wrong format! This command should be like 'stopprivate <user>'");
                        }
                    } else {
                        toServer.println(command);
                    }
                }
            }
            toServerSocket.close();
        } catch (IOException e) {
        }
    }

    private static void printDataFromServer() throws IOException {
        String line;
        loop:
        while ((line = fromServer.readLine()) != null) {
            if ("$FIN".equals(line)) {
                break;
            } else if (line.isEmpty()) {
                System.out.println();
            } else if ("$P2P".equals(line)) {
                line = fromServer.readLine();
                String[] you_to_ip_port = line.split("_");

                synchronized (p2pThreads) {
                    for (P2PThread p2pThread : p2pThreads) {
                        if (p2pThread.isP2PConnected(you_to_ip_port[1])) {
                            System.out.println(String.format("You don not need to do this. There is already a connection between you and %1$s.", you_to_ip_port[1]));
                            continue loop;
                        }
                    }
                    synchronized (myName) {
                        myName = you_to_ip_port[0];
                    }
                    Socket socket = new Socket(InetAddress.getByName(you_to_ip_port[2]), Integer.parseInt(you_to_ip_port[3]) + 1);//avoid conflict with toServerSocket to server on localhost
                    executorService.execute(new P2PThread(socket, you_to_ip_port[0], you_to_ip_port[1]));
                }
            } else {
                System.out.print(line);
            }
        }
        toServerSocket.close();
        synchronized (p2pThreads) {
            if (p2pThreads.isEmpty()) {
                isRunning = false;
                System.exit(0);
            }
        }
    }

    private static class P2PThread implements Runnable {
        private Socket socket;
        private boolean isRunning;
        private String peerA;//P2P request name
        private String peerB;//P2P response name

        private BufferedReader fromPeer;
        private PrintWriter toPeer;

        /**
         * P2P response call this to accept
         */
        public P2PThread(Socket socket) {
            this.socket = socket;
        }

        /**
         * P2P request call this to build a connection
         */
        public P2PThread(Socket socket, String you, String to) {
            this.socket = socket;
            peerA = you;
            peerB = to;
        }

        public boolean isP2PConnected(String peerName) {
            return peerName.equals(peerA) || peerName.equals(peerB);
        }

        public void sendMsg(String to, String msg) {
            String you;
            if (peerA.equals(to)) {
                you = peerB;
            } else if (peerB.equals(to)) {
                you = peerA;
            } else {
                throw new RuntimeException("P2P peers do not match!");
            }
            toPeer.println(you + "(private): " + msg + '\n');
        }

        public void stop() {
            isRunning = false;
            try {
                toPeer.println(myName + " has disconnected The P2P connection with you.\n\n$FIN");
                socket.close();
            } catch (IOException e) {
            }
            synchronized (p2pThreads) {
                p2pThreads.remove(P2PThread.this);
            }
        }

        @Override
        public void run() {
            try {
                isRunning = true;
                synchronized (p2pThreads) {
                    p2pThreads.add(P2PThread.this);
                }
                fromPeer = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                toPeer = new PrintWriter(socket.getOutputStream(), true);
                if (peerA == null) {//is initialised as a server
                    toPeer.println("startprivate");//ask P2P request names
                }
                while (isRunning) {
                    String line;
                    try {
                        while ((line = fromPeer.readLine()) != null) {
                            if ("startprivate".equals(line)) {
                                toPeer.println("$P2P\n" + peerA + "_" + peerB);
                            } else if ("$P2P".equals(line)) {
                                line = fromPeer.readLine();
                                String[] request_you = line.split("_");
                                peerA = request_you[0];
                                peerB = request_you[1];
                                synchronized (myName) {
                                    myName = request_you[1];
                                }
                                toPeer.println(String.format("You have built a P2P connection with %1$s successfully.\n", peerB));
                            } else if ("$FIN".equals(line)) {
                                break;
                            } else if (line.isEmpty()) {
                                System.out.println();
                            } else {
                                System.out.print(line);
                            }
                        }
                        isRunning = false;
                        if (toServerSocket.isClosed()) {
                            System.exit(0);
                        }
                    } catch (IOException e) {
                    }
                }
                socket.close();
            } catch (IOException e) {
            } finally {
                synchronized (p2pThreads) {
                    p2pThreads.remove(P2PThread.this);
                }
            }
        }
    }
}
