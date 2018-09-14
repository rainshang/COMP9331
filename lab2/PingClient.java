import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class PingClient {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Arguments must meet this format: host port");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);

        DatagramSocket datagramSocket = new DatagramSocket();
        datagramSocket.setSoTimeout(1000);

        for (int i = 0; i < 10; i++) {
            String data = String.format("PING %d %d \r\n", i, System.currentTimeMillis());
            byte[] dataBytes = data.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(dataBytes, dataBytes.length, InetAddress.getByName(host), port);
            datagramSocket.send(sendPacket);

            try {
                DatagramPacket receivePacket = new DatagramPacket(new byte[1024], 1024);
                datagramSocket.receive(receivePacket);

                long sendTime = Long.parseLong(new String(receivePacket.getData()).split(" ")[2]);
                System.out.println(String.format("ping to %s, seq = %d, rtt = %d ms", host, i, System.currentTimeMillis() - sendTime));
            } catch (SocketTimeoutException e) {
//                System.out.println(String.format("ping to %s, seq = %d, sorry timeout...", host, i));
                continue;
            }
        }
    }
}