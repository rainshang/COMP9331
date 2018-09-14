import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class DrvExt {
    private final static String MODE_NORMAL = "-n";
    private final static String MODE_POISONED_REVERSE = "-p";
    private final static String MODE_EXTEND = "-e";
    private final static int SEND_ROUTE_TABLE_INTERVAL = 5 * 1000;
    private final static int HEARTBEAT_TIMEOUT = 3 * SEND_ROUTE_TABLE_INTERVAL;
    private final static int TIMER_PRECISION = 1000;
    private final static int INFINITE_HOP_COUNT = 16;

    // msg format: nodeId msgBody
    private final static String MSG_PARSE_DIVIDER = " ";
    private final static int MSG_PARSE_INDEX_SOURCE_NODE = 0;
    private final static int MSG_PARSE_INDEX_BODY = 1;

    private final static String TABLE_ELEMENT_DIVIDER = ",";

    public static void main(String[] args) throws IOException {
        if (args.length == 3) {//basic mode
            new DrvExt(args[0], Integer.parseInt(args[1]), args[2], MODE_NORMAL);
        } else if (args.length == 4) {
            new DrvExt(args[0], Integer.parseInt(args[1]), args[2], args[3]);
        } else {
            throw new RuntimeException("Arguments error! Please call like 'java DvrPr A 2000 configA.txt [-n|-p|-e]'");
        }
    }

    private String nodeId;
    private int port;
    private String mode;
    private Map<String, Neighbour> neighbours;
    private Map<String, RouteTableElement> routeTable;
    private DatagramSocket UDPSocket;

    //just for print, not relevant to RIP
    private final static int STABILITY_OUTPUT_DURATION = 30 * 1000;
    private long lastUpdateTimestamp;
    private boolean needPrint;

    private boolean isFirstPrintInPR;

    private DrvExt(final String nodeId, final int port, String configFile, String mode) throws IOException {
        this.nodeId = nodeId;
        this.port = port;
        this.mode = mode;

        FileInputStream fileInputStream = new FileInputStream(configFile);
        InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream);
        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
        String line;
        boolean isFirstLine = true;
        neighbours = new HashMap<>();
        routeTable = new HashMap<>();
        while ((line = bufferedReader.readLine()) != null) {
            if (isFirstLine) {//first line, the number of neighbours
                isFirstLine = false;
            } else {
                String[] ss = line.split(" ");
                Neighbour neighbour;
                if (ss.length == 3) {
                    neighbour = new Neighbour(ss[0], Float.parseFloat(ss[1]), Integer.parseInt(ss[2]), HEARTBEAT_TIMEOUT);
                } else if (ss.length == 4) {
                    neighbour = new PRNeighbour(ss[0], Float.parseFloat(ss[1]), Float.parseFloat(ss[2]), Integer.parseInt(ss[3]), HEARTBEAT_TIMEOUT);
                } else {
                    throw new RuntimeException("Line in config file should be like 'B 2 2001' or 'Y 4 60 6001'!");
                }
                neighbours.put(neighbour.getNodeId(), neighbour);
                routeTable.put(neighbour.getNodeId(), new RouteTableElement(neighbour.getNodeId(), neighbour.getCost(), neighbour.getNodeId(), 1));
            }
        }
        lastUpdateTimestamp = System.currentTimeMillis();
        needPrint = true;
        isFirstPrintInPR = true;

        UDPSocket = new DatagramSocket(port);

        /**
         * listen {@link port}, to receive routeTables from neighbours
         */
        new Thread() {
            @Override
            public void run() {
                super.run();
                while (true) {
                    DatagramPacket datagramPacket = new DatagramPacket(new byte[1024], 1024);
                    try {
                        UDPSocket.receive(datagramPacket);
                        String rawData = new String(datagramPacket.getData());
                        //                        System.out.println(rawData);
                        String[] ss = rawData.split(MSG_PARSE_DIVIDER);


                        String sourceNodeId = ss[MSG_PARSE_INDEX_SOURCE_NODE];
                        Neighbour sourceNode = neighbours.get(sourceNodeId);
                        sourceNode.setHeartBeatCountdown(HEARTBEAT_TIMEOUT);

                        RouteTableElement sourceInRTE = routeTable.get(sourceNodeId);
                        if (sourceInRTE.getHopCount() == INFINITE_HOP_COUNT) {//if sourceNode is dead in route table, set it alive
                            sourceInRTE.setHopCount(1);
                            sourceInRTE.setCost(sourceNode.getCost());
                            sourceInRTE.setViaNeighbourNodeId(sourceNodeId);

                            lastUpdateTimestamp = System.currentTimeMillis();
                            needPrint = true;
                        }

                        //set all route history via comingFromNode dead (will be updated by this new data)
                        if (MODE_EXTEND.equalsIgnoreCase(DrvExt.this.mode)) {
                            synchronized (routeTable) {
                                for (Entry<String, RouteTableElement> rtEntry : routeTable.entrySet()) {
                                    RouteTableElement routeTableElement = rtEntry.getValue();
                                    if (sourceNodeId.equals(routeTableElement.getViaNeighbourNodeId())) {
                                        if (!sourceNodeId.equals(routeTableElement.getDestinationNodeId())) {//jump this neighbour itself
                                            routeTableElement.setHopCount(INFINITE_HOP_COUNT);
                                        }
                                    }
                                }
                            }
                        }

                        String rtRawData = ss[MSG_PARSE_INDEX_BODY].trim();
                        if (!rtRawData.isEmpty()) {
                            ss = rtRawData.split(TABLE_ELEMENT_DIVIDER);
                            for (String s : ss) {
                                RouteTableElement comingRTE = new RouteTableElement(s);
                                if (!nodeId.equals(comingRTE.getDestinationNodeId())) {// jump sourceNode to self
                                    if (MODE_POISONED_REVERSE.equalsIgnoreCase(DrvExt.this.mode)) {
                                        if (comingRTE.getHopCount() == INFINITE_HOP_COUNT) {
                                            for (Entry<String, RouteTableElement> rtEntry : routeTable.entrySet()) {
                                                RouteTableElement routeTableElement = rtEntry.getValue();
                                                if (sourceNodeId.equals(routeTableElement.getViaNeighbourNodeId())
                                                        && comingRTE.getDestinationNodeId().equals(routeTableElement.getDestinationNodeId())
                                                        && routeTableElement.getHopCount() < INFINITE_HOP_COUNT) {
                                                    routeTableElement.setHopCount(INFINITE_HOP_COUNT);//set disconnecting neighbour's and route's via this beighbour hop to INFINITE_HOP_COUNT directly

                                                    lastUpdateTimestamp = System.currentTimeMillis();
                                                    needPrint = true;
                                                }
                                            }
                                            return;
                                        }
                                    }

                                    RouteTableElement existingRTE = routeTable.get(comingRTE.getDestinationNodeId());
                                    if (existingRTE != null) {
                                        if (sourceNodeId.equals(existingRTE.getViaNeighbourNodeId())) {//same sourceNode (via)
                                            if (comingRTE.getCost() + sourceNode.getCost() != existingRTE.getCost()) {//cost changes
                                                updateRouteTable(sourceNode, comingRTE);
                                            }
                                        } else {
                                            if (existingRTE.getHopCount() == INFINITE_HOP_COUNT) {//unreachable destination can access now
                                                updateRouteTable(sourceNode, comingRTE);
                                            } else {
                                                float newCost = sourceNode.getCost() + comingRTE.getCost();
                                                if (existingRTE.getCost() > newCost) {// old cost > new
                                                    updateRouteTable(sourceNode, comingRTE);
                                                } else if (existingRTE.getCost() == newCost) {// old cost == new
                                                    if (existingRTE.getHopCount() > comingRTE.getHopCount() + 1) {// old hop > new hop
                                                        updateRouteTable(sourceNode, comingRTE);
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        updateRouteTable(sourceNode, comingRTE);
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            private void updateRouteTable(Neighbour sourceNode, RouteTableElement comingRTE) {
                comingRTE.setViaNeighbourNodeId(sourceNode.getNodeId());
                int previousHop = comingRTE.getHopCount();
                comingRTE.setHopCount(comingRTE.getHopCount() + 1);
                routeTable.put(comingRTE.getDestinationNodeId(), comingRTE);
                if (previousHop != comingRTE.getHopCount()) {// equal means previous is infinite already
                    comingRTE.setCost(sourceNode.getCost() + comingRTE.getCost());
                    lastUpdateTimestamp = System.currentTimeMillis();
                    needPrint = true;
                }

            }
        }.start();

        /**
         * send routeTable to neighbours very {@link TIMER_PRECISION}
         */
        new Thread() {
            @Override
            public void run() {
                super.run();
                while (true) {
                    for (Entry<String, Neighbour> entry : neighbours.entrySet()) {
                        try {
                            String rawData = nodeId + MSG_PARSE_DIVIDER + routeTableToString();
                            byte[] data = rawData.getBytes();
                            DatagramPacket datagramPacket = new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), entry.getValue().getPort());
                            UDPSocket.send(datagramPacket);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    try {
                        Thread.sleep(SEND_ROUTE_TABLE_INTERVAL);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.start();

        /**
         * timer, check and set {@link Neighbour#heartBeatCountdown} very {@link TIMER_PRECISION}
         */
        new Thread() {
            @Override
            public void run() {
                super.run();
                while (true) {
                    try {
                        Thread.sleep(TIMER_PRECISION);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    for (Entry<String, Neighbour> nEntry : neighbours.entrySet()) {
                        int currentHBCD = nEntry.getValue().getHeartBeatCountdown() - TIMER_PRECISION;
                        nEntry.getValue().setHeartBeatCountdown(currentHBCD);
                        if (currentHBCD <= 0) {//timeout
                            String timeoutNeighbourId = nEntry.getKey();
                            for (Entry<String, RouteTableElement> rtEntry : routeTable.entrySet()) {
                                RouteTableElement routeTableElement = rtEntry.getValue();
                                if (routeTableElement.getHopCount() < INFINITE_HOP_COUNT
                                        && timeoutNeighbourId.equals(routeTableElement.getViaNeighbourNodeId())//delete this neighbour and neighbours via this neighbour in rt
                                        ) {
                                    routeTableElement.setHopCount(INFINITE_HOP_COUNT);

                                    lastUpdateTimestamp = System.currentTimeMillis();
                                    needPrint = true;
                                }
                            }
                        }
                    }

                    //print, just for this assignment, not relevant to RIP
                    if (needPrint && System.currentTimeMillis() - lastUpdateTimestamp > STABILITY_OUTPUT_DURATION) {
                        System.out.println("This is the route table of node " + nodeId);
                        for (Entry<String, RouteTableElement> entry : routeTable.entrySet()) {
                            if (entry.getValue().getHopCount() < INFINITE_HOP_COUNT) {
                                System.out.println(String.format("Shortest path to node %1$s: the next hop is %2$s and the cost is %3$s", entry.getKey(), entry.getValue().getViaNeighbourNodeId(), entry.getValue().getCost()));
                            }
                        }
                        needPrint = false;

                        if (MODE_POISONED_REVERSE.equalsIgnoreCase(DrvExt.this.mode) && isFirstPrintInPR) {
                            isFirstPrintInPR = false;

                            if (neighbours.entrySet().iterator().next().getValue() instanceof PRNeighbour) {
                                synchronized (routeTable) {
                                    routeTable.clear();
                                    for (Entry<String, Neighbour> entry : neighbours.entrySet()) {
                                        PRNeighbour prNeighbour = (PRNeighbour) entry.getValue();
                                        prNeighbour.setSecondCost();
                                        routeTable.put(prNeighbour.getNodeId(), new RouteTableElement(prNeighbour.getNodeId(), prNeighbour.getCost(), prNeighbour.getNodeId(), 1));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.start();
    }

    private String routeTableToString() {
        StringBuilder stringBuilder = new StringBuilder();
        for (Entry<String, RouteTableElement> entry : routeTable.entrySet()) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append(TABLE_ELEMENT_DIVIDER);
            }
            stringBuilder.append(entry.getValue());
        }
        return stringBuilder.toString();
    }

    private class Neighbour {
        private final String nodeId;
        protected float cost;
        private final int port;
        private int heartBeatCountdown;

        public Neighbour(String nodeId, float cost, int port, int heartBeatCountdown) {
            this.nodeId = nodeId;
            this.cost = cost;
            this.port = port;
            this.heartBeatCountdown = heartBeatCountdown;
        }

        public String getNodeId() {
            return nodeId;
        }

        public float getCost() {
            return cost;
        }

        public int getPort() {
            return port;
        }

        public int getHeartBeatCountdown() {
            return heartBeatCountdown;
        }

        public void setHeartBeatCountdown(int heartBeatCountdown) {
            this.heartBeatCountdown = heartBeatCountdown < 0 ? 0 : heartBeatCountdown;
        }
    }

    private class PRNeighbour extends Neighbour {
        private final float secondCost;

        public PRNeighbour(String nodeId, float cost, float secondCost, int port, int heartBeatDowncount) {
            super(nodeId, cost, port, heartBeatDowncount);
            this.secondCost = secondCost;
        }

        public float setSecondCost() {
            return cost = secondCost;
        }

    }

    private class RouteTableElement {
        private final static String DIVIDER = "_";

        private final String destinationNodeId;
        private float cost;

        private String viaNeighbourNodeId;
        private int hopCount;

        public RouteTableElement(String destinationNodeId, float cost, String viaNeighbourNodeId, int hopCount) {
            this.destinationNodeId = destinationNodeId;
            this.cost = cost;
            this.viaNeighbourNodeId = viaNeighbourNodeId;
            this.hopCount = hopCount;
        }

        public RouteTableElement(String rawData) {
            String[] ss = rawData.split(DIVIDER);
            destinationNodeId = ss[0];
            cost = Float.parseFloat(ss[1]);
            viaNeighbourNodeId = ss[2];
            hopCount = Integer.parseInt(ss[3]);
        }

        public String getDestinationNodeId() {
            return destinationNodeId;
        }

        public float getCost() {
            return cost;
        }

        public void setCost(float cost) {
            this.cost = cost;
        }

        public String getViaNeighbourNodeId() {
            return viaNeighbourNodeId;
        }

        public void setViaNeighbourNodeId(String viaNeighbourNodeId) {
            this.viaNeighbourNodeId = viaNeighbourNodeId;
        }

        public int getHopCount() {
            return hopCount;
        }

        public void setHopCount(int hopCount) {
            this.hopCount = hopCount > INFINITE_HOP_COUNT ? INFINITE_HOP_COUNT : hopCount;
        }

        @Override
        public String toString() {
            return destinationNodeId + DIVIDER + cost + DIVIDER + viaNeighbourNodeId + DIVIDER + hopCount;
        }
    }

}