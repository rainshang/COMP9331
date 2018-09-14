import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Ethan on 20/4/17.
 */

public class Server {
    private final static int MAX_TRY_TIMES = 3;

    private static Map<String, User> userData;

    private static int blockDuration;
    private static int timeout;

    private static ExecutorService executorService;
    private static List<User> blockedLoginUsers;
    private static List<User> loginLog;
    private static List<ServerThread> runningConnections;

    /**
     * the arguments should be server_port (int), block_duration (int, seconds), timeout (int, seconds)
     *
     * @param args
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new RuntimeException("Arguments error! Needs server_port, block_duration, timeout");
        }
        loadUserData();

        int serverPort = Integer.parseInt(args[0]);
        blockDuration = Integer.parseInt(args[1]);
        timeout = Integer.parseInt(args[2]);
        blockedLoginUsers = new ArrayList<>();
        loginLog = new ArrayList<>();
        runningConnections = new ArrayList<>();

        executorService = Executors.newCachedThreadPool();
        ServerSocket serverSocket = new ServerSocket(serverPort);
        while (true) {
            Socket clientSocket = serverSocket.accept();
            checkBlockedLoginUsers();
            executorService.execute(new ServerThread(clientSocket));
        }
    }

    private static void loadUserData() throws Exception {
        FileReader fileReader = new FileReader("credentials.txt");
        BufferedReader bufferedReader = new BufferedReader(fileReader);
        userData = new HashMap<>();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            String[] uer_pwd = line.split(" ");
            userData.put(uer_pwd[0], new User(uer_pwd[0], uer_pwd[1]));
        }
        bufferedReader.close();
        fileReader.close();
    }

    private synchronized static void checkBlockedLoginUsers() {
        Iterator<User> iterator = blockedLoginUsers.iterator();
        while (iterator.hasNext()) {
            User user = iterator.next();
            if (user.lastActiveTime + blockDuration < System.currentTimeMillis() / 1000) {
                iterator.remove();
                user.lastActiveTime = 0;
            }
        }
    }

    private synchronized static void addBlockedLoginUser(User user) {
        user.lastActiveTime = (int) (System.currentTimeMillis() / 1000);
        blockedLoginUsers.add(user);
    }

    private static class ServerThread implements Runnable {

        private Socket socket;
        private boolean isRunning;

        private BufferedReader fromClient;
        private PrintWriter toClient;

        private User user;

        private boolean isWaitingForConfirm;
        private String userToBlock;
        private String userToUnBlock;

        public ServerThread(Socket socket) {
            this.socket = socket;
            isRunning = true;
        }

        @Override
        public void run() {
            try {
                fromClient = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                toClient = new PrintWriter(socket.getOutputStream(), true);
                requestUserName();
                socket.close();
            } catch (IOException e) {
            }
        }

        private void requestUserName() throws IOException {
            sendDataToClient("Username:");
            String userName;
            do {
                userName = fromClient.readLine();
                user = userData.get(userName);
                if (user == null) {
                    sendDataToClient(String.format("'%1$s' hasn't been registered!\n\nUsername:", userName));
                } else {
                    if (!blockedLoginUsers.contains(user)) {
                        requestPwd();
                        break;
                    } else {
                        sendFinToClient(String.format("Your account is blocked due to multiple login failures. Please try again %1$ds later.", blockDuration - (System.currentTimeMillis() / 1000 - user.lastActiveTime)));
                    }
                }
            } while (user == null);
        }

        private void requestPwd() throws IOException {
            sendDataToClient("Password:");
            for (int i = 0; i < MAX_TRY_TIMES; i++) {
                String userPwd = fromClient.readLine();
                if (user.userPwd.equals(userPwd)) {
                    sendDataToClientln("Welcome to the greatest messaging application ever!");
                    afterLogin();
                    break;
                } else if (i == MAX_TRY_TIMES - 1) {
                    sendFinToClient(String.format("Invalid Password. Your account has been blocked. Please try again %1$ds later.", blockDuration));
                    addBlockedLoginUser(user);
                } else {
                    sendDataToClient("Invalid Password. Please try again.\n\nPassword:");
                }
            }
        }

        private void afterLogin() throws IOException {
            synchronized (Server.class) {
                user.lastActiveTime = (int) (System.currentTimeMillis() / 1000);
                user.loginTime = (int) (System.currentTimeMillis() / 1000);
                loginLog.add(user);
                runningConnections.add(ServerThread.this);
            }
            doBroadcast(user.userName + " logged in.", false);
            executorService.execute(new Runnable() {//sending msg thread
                @Override
                public void run() {
                    while (isRunning) {
                        if (user.lastActiveTime + timeout > System.currentTimeMillis() / 1000) {
                            synchronized (user) {
                                while (!user.msgToPush.isEmpty()) {
                                    Message toSendMessage = user.msgToPush.poll();
                                    if (!user.blockedUsers.contains(toSendMessage.from)) {
                                        sendDataToClientln(String.format("%1$s: %2$s", toSendMessage.from, toSendMessage.msg));
                                    }
                                }
                            }
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        } else {
                            sendFinToClient("You are forced log out by server because of long-time no activity.\nPlease re-login.");
                            synchronized (Server.class) {
                                runningConnections.remove(ServerThread.this);
                            }
                            doBroadcast(user.userName + " logged out.", false);
                            break;
                        }
                    }
                }
            });

            loop:
            while (isRunning) {
                String clientCommand = fromClient.readLine();
                if (clientCommand != null) {
                    int originalActiveTime = user.lastActiveTime;
                    user.lastActiveTime = (int) (System.currentTimeMillis() / 1000);

                    String[] params = clientCommand.trim().split("\\s+");
                    if (isWaitingForConfirm) {
                        if (params.length == 1) {
                            if ("yes".equals(params[0])) {
                                if (userToBlock != null) {
                                    synchronized (user) {
                                        user.blockedUsers.add(userToBlock);
                                        sendDataToClientln(String.format("You have blocked %1%s successfully.", userToBlock));
                                        userToBlock = null;
                                        isWaitingForConfirm = false;
                                    }
                                } else if (userToUnBlock != null) {
                                    synchronized (user) {
                                        user.blockedUsers.remove(userToUnBlock);
                                        sendDataToClientln(String.format("You have unblocked %1%s successfully.", userToUnBlock));
                                        userToUnBlock = null;
                                        isWaitingForConfirm = false;
                                    }
                                }
                                continue;
                            } else if ("no".equals(params[0])) {
                                if (userToBlock != null) {
                                    synchronized (ServerThread.this) {
                                        isWaitingForConfirm = false;
                                        userToBlock = null;
                                    }
                                } else if (userToUnBlock != null) {
                                    synchronized (ServerThread.this) {
                                        isWaitingForConfirm = false;
                                        userToUnBlock = null;
                                    }
                                }
                                continue;
                            }
                        }
                        sendDataToClient("'%1$s' is not an expected command\n\nPlease type 'yes'/'no' to confirm:");
                    } else {
                        if ("message".equals(params[0])) {
                            if (params.length >= 3) {
                                User to = userData.get(params[1]);
                                if (to != null) {
                                    if (user != to) {
                                        synchronized (to) {
                                            if (!to.blockedUsers.contains(user.userName)) {
                                                to.msgToPush.offer(new Message(clientCommand.substring(params[0].length() + params[1].length() + 2), user.userName));
                                            } else {
                                                sendDataToClientln(String.format("Sorry, it seems that '%1$s' has blocked you", params[1]));
                                            }
                                        }
                                    } else {
                                        sendDataToClientln("You cannot send message to yourself.");
                                    }
                                } else {
                                    sendDataToClientln(String.format("'%1$s' is not existing.", params[1]));
                                }
                            } else {
                                sendDataToClientln("Wrong format! This command should be like 'message <user> <message>'");
                            }
                        } else if ("broadcast".equals(params[0])) {
                            if (params.length >= 2) {
                                doBroadcast(clientCommand.substring(params[0].length() + 1), true);
                            } else {
                                sendDataToClientln("Wrong format! This command should be like 'broadcast <message>'");
                            }
                        } else if ("whoelse".equals(params[0])) {
                            if (params.length == 1) {
                                synchronized (Server.class) {
                                    List<String> onlineUsers = new ArrayList<>();
                                    for (ServerThread runningThread : runningConnections) {
                                        if (runningThread.user != user) {
                                            onlineUsers.add(runningThread.user.userName);
                                        }
                                    }
                                    sendListToClient("Currently online users are:", onlineUsers);
                                }
                            } else {
                                sendDataToClientln("Wrong format! This command should be like 'broadcast'");
                            }
                        } else if ("whoelsesince".equals(params[0])) {
                            if (params.length == 2) {
                                try {
                                    int sinceSecond = Integer.parseInt(params[1]);
                                    synchronized (Server.class) {
                                        List<String> loggedUsers = new ArrayList<>();
                                        for (User loggedUser : loginLog) {
                                            if (loggedUser != user) {
                                                if (loggedUser.loginTime + sinceSecond > System.currentTimeMillis() / 1000) {
                                                    loggedUsers.add(loggedUser.userName);
                                                }
                                            }
                                        }
                                        sendListToClient(String.format("Users who logged in %1$ds are:", sinceSecond), loggedUsers);
                                    }
                                } catch (NumberFormatException e) {
                                    sendDataToClientln("Wrong format! This command should be like 'whoelsesince <seconds>'");
                                }
                            } else {
                                sendDataToClientln("Wrong format! This command should be like 'whoelsesince <seconds>'");
                            }
                        } else if ("block".equals(params[0])) {
                            if (params.length == 2) {
                                User toBlock = userData.get(params[1]);
                                if (toBlock != null) {
                                    if (user != toBlock) {
                                        synchronized (ServerThread.this) {
                                            isWaitingForConfirm = true;
                                            userToBlock = toBlock.userName;
                                            sendDataToClient(String.format("Are you sure to block '%1$s'?\n\nType 'yes'/'no' to confirm:", userToBlock));
                                        }
                                    } else {
                                        sendDataToClientln("You cannot block yourself.");
                                    }
                                } else {
                                    sendDataToClientln(String.format("'%1$s' does not exist.", params[1]));
                                }
                            } else {
                                sendDataToClientln("Wrong format! This command should be like 'block <user>'");
                            }
                        } else if ("unblock".equals(params[0])) {
                            if (params.length == 2) {
                                User toUnblock = userData.get(params[1]);
                                if (toUnblock != null) {
                                    if (user != toUnblock) {
                                        synchronized (ServerThread.this) {
                                            isWaitingForConfirm = true;
                                            userToUnBlock = toUnblock.userName;
                                            sendDataToClient(String.format("Are you sure to unblock '%1$s'?\n\nType 'yes'/'no' to confirm:", userToUnBlock));
                                        }
                                    } else {
                                        sendDataToClientln("You cannot unblock yourself.");
                                    }
                                } else {
                                    sendDataToClientln(String.format("'%1$s' does not exist.", params[1]));
                                }
                            } else {
                                sendDataToClientln("Wrong format! This command should be like 'block <user>'");
                            }
                        } else if ("logout".equals(params[0])) {
                            if (params.length == 1) {
                                synchronized (Server.class) {
                                    runningConnections.remove(ServerThread.this);
                                    sendFinToClient("");
                                }
                                doBroadcast(user.userName + " logged out.", false);
                                break;
                            } else {
                                sendDataToClientln("Wrong format! This command should be like 'logout'");
                            }
                        } else if ("startprivate".equals(params[0])) {
                            User to = userData.get(params[1]);
                            if (to != null) {
                                if (user != to) {
                                    synchronized (to) {
                                        if (!to.blockedUsers.contains(user.userName)) {
                                            synchronized (runningConnections) {
                                                for (ServerThread serverThread : runningConnections) {
                                                    if (serverThread.user.userName.equals(params[1])) {
                                                        sendDataToClient("$P2P\n" + user.userName + "_" + params[1] + '_' + serverThread.socket.getInetAddress().getHostAddress() + "_" + serverThread.socket.getPort());//you_to_ip_port
                                                        continue loop;
                                                    }
                                                }
                                                sendDataToClientln(String.format("'%1$s' is not online currently.", params[1]));
                                            }
                                        } else {
                                            sendDataToClientln(String.format("Sorry, it seems that '%1$s' has blocked you", params[1]));
                                        }
                                    }
                                } else {
                                    sendDataToClientln("You cannot start a private connection to yourself.");
                                }
                            } else {
                                sendDataToClientln(String.format("'%1$s' is not existing.", params[1]));
                            }
                        } else {
                            user.lastActiveTime = originalActiveTime;
                            sendDataToClientln(String.format("'%1$s' is not a supported command!", clientCommand));
                        }
                    }
                } else {
                    synchronized (Server.class) {
                        runningConnections.remove(ServerThread.this);
                    }
                    doBroadcast(user.userName + " logged out.", false);
                    break;
                }
            }
        }

        private void sendDataToClient(String data) {
            toClient.println(data);
        }

        private void sendDataToClientln(String data) {
            toClient.println(data + '\n');
        }

        private void sendFinToClient(String data) {
            isRunning = false;
            toClient.println(data + "\n\n$FIN");
        }

        private void sendListToClient(String pretext, List<String> items) {
            StringBuilder stringBuilder = new StringBuilder();
            for (String item : items) {
                stringBuilder.append(item + "\n\n");
            }
            toClient.println(pretext + "\n\n" + stringBuilder.toString());
        }

        private void doBroadcast(String msg, boolean withBlockTip) {
            synchronized (Server.class) {
                Message message = new Message(msg, user.userName);
                List<String> blockedUsers = new ArrayList<>();
                for (ServerThread runningThread : runningConnections) {
                    if (!runningThread.user.blockedUsers.contains(user.userName)) {
                        if (runningThread.user != user) {
                            runningThread.user.msgToPush.offer(message);
                        }
                    } else {
                        blockedUsers.add(runningThread.user.userName);
                    }
                }
                if (withBlockTip && !blockedUsers.isEmpty()) {
                    sendListToClient("Your broadcast has been blocked by these users:", blockedUsers);
                }
            }
        }
    }

    private static class User {
        String userName;
        String userPwd;
        int lastActiveTime;
        int loginTime;
        List<String> blockedUsers;//NOTICE, it's String NOT User!
        Queue<Message> msgToPush;

        public User(String userName, String userPwd) {
            this.userName = userName;
            this.userPwd = userPwd;
            blockedUsers = new ArrayList<>();
            msgToPush = new ArrayDeque<>();
        }

    }

    private static class Message {
        String msg;
        String from;

        public Message(String msg, String from) {
            this.msg = msg;
            this.from = from;
        }
    }
}
