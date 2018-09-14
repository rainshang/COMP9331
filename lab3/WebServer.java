import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class WebServer {

    private final static int PARAMETERS_LINE = 0;
    private final static String ONLY_GET_WARNING = "This WebSever only support GET request right now...";
    private final static String HTTP_RESPONSE_HEADER_BODY_DIVIDER = "\r\n";


    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Arguments must meet this format: port");
            return;
        }
        ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]));
        while (true) {
            Socket socket = serverSocket.accept();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter printWriter = new PrintWriter(socket.getOutputStream(), true);
            String inputLine = null;
            String parameterS = null;
            int lineCount = 0;
            while ((inputLine = bufferedReader.readLine()) != null && !inputLine.isEmpty()) {
                if (lineCount == PARAMETERS_LINE) {
                    parameterS = inputLine;
                }
                System.out.println(inputLine);
                lineCount++;
            }
            try {
                String[] parameters = parameterS.split(" ");
                if ("GET".equalsIgnoreCase(parameters[0])) {
                    BufferedReader requestFile = new BufferedReader(new InputStreamReader(new FileInputStream(parameters[1].substring(1))));

                    printWriter.write(getResponseHead(parameters[2], 200, "")
                            + HTTP_RESPONSE_HEADER_BODY_DIVIDER);

                    while ((inputLine = requestFile.readLine()) != null) {
                        printWriter.println(inputLine);
                    }
                } else {
                    printWriter.write(getResponseHead(parameters[2], 503, ONLY_GET_WARNING)
                            + HTTP_RESPONSE_HEADER_BODY_DIVIDER
                            + getMsgBody(ONLY_GET_WARNING));
                }
            } catch (Exception e) {
                e.printStackTrace();
                printWriter.write(getResponseHead("HTTP", 404, "Not Found"));
            }
            printWriter.close();
            socket.close();
        }
    }

    private static String getResponseHead(String requestHttp, int responseCode, String responseCodeDescription) {
        return String.format("%1$s %2$d %3$s\n", requestHttp, responseCode, responseCodeDescription);
    }

    private static String getMsgBody(String msg) {
        return String.format("<html><h1>%1$s</h1></html>", msg);
    }
}