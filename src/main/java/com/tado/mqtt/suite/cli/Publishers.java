package com.tado.mqtt.suite.cli;

import com.tado.mqtt.suite.client.ClientPublishTask;
import org.apache.commons.io.FileUtils;
import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.UTF8Buffer;
import org.fusesource.hawtdispatch.DispatchQueue;
import org.fusesource.mqtt.client.*;
import org.joda.time.format.PeriodFormat;
import org.joda.time.Period;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.fusesource.hawtdispatch.Dispatch.createQueue;

/**
 * Created by kuceram on 19/05/14.
 */
public class Publishers {

    private MQTT mqtt = new MQTT();
    private UTF8Buffer topic;
    private Buffer body;
    private boolean debug;
    private boolean prefixCounter;
    private boolean retain;
    private QoS qos = QoS.AT_MOST_ONCE;
    private int messageCount = 1;
    private long sleep;
    private int clientCount = 1;

    private AtomicInteger messagesSent = new AtomicInteger(0);
    private AtomicInteger errorMessages = new AtomicInteger(0);
    private AtomicInteger errorConnections = new AtomicInteger(0);
    private AtomicLong size = new AtomicLong(0);
    private long startTimeNanosec;

    private static void displayHelpAndExit(int exitCode) {
        stdout("");
        stdout("This is a simple mqtt client that will publish to a topic.");
        stdout("");
        stdout("Arguments: [-h host] [-k keepalive] [-c] [-i id] [-u username [-p password]]");
        stdout("           [--will-topic topic [--will-payload payload] [--will-qos qos] [--will-retain]]");
        stdout("           [--client-count count] [--msg-count count] [--client-sleep sleep]");
        stdout("           [-d] [-q qos] [-r] -t topic ( -pc | -m message | -z | -f file )");
        stdout("");
        stdout("");
        stdout(" -h : mqtt host uri to connect to. Defaults to tcp://localhost:1883.");
        stdout(" -k : keep alive in seconds for this client. Defaults to 60.");
        stdout(" -c : disable 'clean session'.");
        stdout(" -i : id to use for this client. Defaults to a random id.");
        stdout(" -u : provide a username (requires MQTT 3.1 broker)");
        stdout(" -p : provide a password (requires MQTT 3.1 broker)");
        stdout(" --will-topic : the topic on which to publish the client Will.");
        stdout(" --will-payload : payload for the client Will, which is sent by the broker in case of");
        stdout("                  unexpected disconnection. If not given and will-topic is set, a zero");
        stdout("                  length message will be sent.");
        stdout(" --will-qos : QoS level for the client Will.");
        stdout(" --will-retain : if given, make the client Will retained.");
        stdout(" -d : display debug info on stderr");
        stdout(" -q : quality of service level to use for the publish. Defaults to 0.");
        stdout(" -r : message should be retained.");
        stdout(" -t : mqtt topic to publish to.");
        stdout(" -m : message payload to send.");
        stdout(" -z : send a null (zero length) message.");
        stdout(" -f : send the contents of a file as the message.");
        stdout(" -pc : prefix a message counter to the message together with client number");
        stdout(" -v : MQTT version to use 3.1 or 3.1.1. (default: 3.1)");
        stdout(" --client-count : the number of simultaneously connected publishClients");
        stdout(" --msg-count : the number of messages to publish per client");
        stdout(" --client-sleep : the number of milliseconds to sleep after publish operation (defaut: 0)");
        stdout("");
        System.exit(exitCode);
    }

    private void displayStatistics() {
        Period executionTime = new Period(startTimeNanosec/1000000, System.nanoTime()/1000000);
        stdout("");
        stdout("------------------------------------------");
        stdout("Statistic of Publishers");
        stdout("------------------------------------------");
        stdout("Messages successfully sent: " + messagesSent.toString());
        stdout("Total time elapsed: " + PeriodFormat.getDefault().print(executionTime));
        if (executionTime.toStandardSeconds().getSeconds() > 0)
            stdout("Message rate: " + (messagesSent.get() / executionTime.toStandardSeconds().getSeconds()) + " msg/sec");
        else
            stdout("Message rate: " + messagesSent.get() + " msg/sec");
        stdout("------------------------------------------");
        stdout("Clients could not connect (failure): " + errorConnections.toString());
        stdout("Messages could not publish (failure): " + errorMessages.toString());
        stdout("------------------------------------------");
        stdout("Total data sent: " + FileUtils.byteCountToDisplaySize(size.get()));
        stdout("------------------------------------------");
        stdout("");
    }

    private static void stdout(Object x) {
        System.out.println(x);
    }
    private static void stderr(Object x) {
        System.err.println(x);
    }

    private static String shift(LinkedList<String> args) {
        if(args.isEmpty()) {
            stderr("Invalid usage: Missing argument");
            displayHelpAndExit(1);
        }
        return args.removeFirst();
    }

    public static void main(String[] args) throws Exception {
        Publishers main = new Publishers();

        // Process the arguments
        LinkedList<String> argl = new LinkedList<String>(Arrays.asList(args));
        while (!argl.isEmpty()) {
            try {
                String arg = argl.removeFirst();
                if ("--help".equals(arg)) {
                    displayHelpAndExit(0);
                } else if ("-v".equals(arg)) {
                    main.mqtt.setVersion(shift(argl));
                } else if ("-h".equals(arg)) {
                    main.mqtt.setHost(shift(argl));
                } else if ("-k".equals(arg)) {
                    main.mqtt.setKeepAlive(Short.parseShort(shift(argl)));
                } else if ("-c".equals(arg)) {
                    main.mqtt.setCleanSession(false);
                } else if ("-i".equals(arg)) {
                    main.mqtt.setClientId(shift(argl));
                } else if ("-u".equals(arg)) {
                    main.mqtt.setUserName(shift(argl));
                } else if ("-p".equals(arg)) {
                    main.mqtt.setPassword(shift(argl));
                } else if ("--will-topic".equals(arg)) {
                    main.mqtt.setWillTopic(shift(argl));
                } else if ("--will-payload".equals(arg)) {
                    main.mqtt.setWillMessage(shift(argl));
                } else if ("--will-qos".equals(arg)) {
                    int v = Integer.parseInt(shift(argl));
                    if( v > QoS.values().length ) {
                        stderr("Invalid qos value : " + v);
                        displayHelpAndExit(1);
                    }
                    main.mqtt.setWillQos(QoS.values()[v]);
                } else if ("--will-retain".equals(arg)) {
                    main.mqtt.setWillRetain(true);
                } else if ("-d".equals(arg)) {
                    main.debug = true;
                } else if ("--client-count".equals(arg)) {
                    main.clientCount = Integer.parseInt(shift(argl));
                } else if ("--msg-count".equals(arg)) {
                    main.messageCount = Integer.parseInt(shift(argl));
                } else if ("--client-sleep".equals(arg)) {
                    main.sleep = Long.parseLong(shift(argl));
                } else if ("-q".equals(arg)) {
                    int v = Integer.parseInt(shift(argl));
                    if( v > QoS.values().length ) {
                        stderr("Invalid qos value : " + v);
                        displayHelpAndExit(1);
                    }
                    main.qos = QoS.values()[v];
                } else if ("-r".equals(arg)) {
                    main.retain = true;
                } else if ("-t".equals(arg)) {
                    main.topic = new UTF8Buffer(shift(argl));
                } else if ("-m".equals(arg)) {
                    main.body = new UTF8Buffer(shift(argl)+"\n");
                } else if ("-z".equals(arg)) {
                    main.body = new UTF8Buffer("");
                } else if ("-f".equals(arg)) {
                    File file = new File(shift(argl));
                    RandomAccessFile raf = new RandomAccessFile(file, "r");
                    try {
                        byte data[] = new byte[(int) raf.length()];
                        raf.seek(0);
                        raf.readFully(data);
                        main.body = new Buffer(data);
                    } finally {
                        raf.close();
                    }
                } else if ("-pc".equals(arg)) {
                    main.prefixCounter = true;
                } else {
                    stderr("Invalid usage: unknown option: " + arg);
                    displayHelpAndExit(1);
                }
            } catch (NumberFormatException e) {
                stderr("Invalid usage: argument not a number");
                displayHelpAndExit(1);
            }
        }

        if (main.topic == null) {
            stderr("Invalid usage: no topic specified.");
            displayHelpAndExit(1);
        }
        if (main.body == null) {
            stderr("Invalid usage: -z -m or -f must be specified.");
            displayHelpAndExit(1);
        }

        main.execute();
        System.exit(0);
    }

    private void execute() {
        startTimeNanosec = System.nanoTime();

        // each client has its own thread and each message is sent in a separate thread
        final CountDownLatch done = new CountDownLatch(clientCount * messageCount);
        DispatchQueue queue = createQueue("mqtt clients queue");
        final ArrayList<ClientPublishTask> clients = new ArrayList<ClientPublishTask>();

        // Handle a Ctrl-C event cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(){
            @Override
            public void run() {
                stdout("");
                stdout("MQTT publishClients shutdown...");

                final CountDownLatch clientClosed = new CountDownLatch(clients.size());
                for(ClientPublishTask client : clients) {
                    client.interrupt(new Callback<Void>() {
                        @Override
                        public void onSuccess(Void value) {
                            clientClosed.countDown();
                            if(debug)
                                stdout("Connection to broker successfully closed");
                        }

                        @Override
                        public void onFailure(Throwable value) {
                            clientClosed.countDown();
                            stderr("Connection close to broker failure!");
                        }
                    });
                }

                try {
                    clientClosed.await(5000, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                displayStatistics();
            }
        });

        // create clients and send the messages
        for (int i=0; i<clientCount; i++) {
            ClientPublishTask clientPublishTask = new ClientPublishTask(mqtt);
            clients.add(clientPublishTask);

            // set client options
            clientPublishTask.setTopic(topic);
            clientPublishTask.setBody(body);
            clientPublishTask.setDebug(debug);
            clientPublishTask.setPrefixCounter(prefixCounter);
            clientPublishTask.setRetain(retain);
            clientPublishTask.setQos(qos);
            clientPublishTask.setMessageCount(messageCount);
            clientPublishTask.setSleep(sleep);
            clientPublishTask.setClientId(Integer.toString(i));
            clientPublishTask.setPublishCallback(new Callback<Integer>() {
                @Override
                public void onSuccess(Integer messageSize) {
                    messagesSent.incrementAndGet();
                    size.addAndGet(new Long(messageSize));
                    done.countDown();
                }

                @Override
                public void onFailure(Throwable value) {
                    errorMessages.incrementAndGet();
                    done.countDown();
                }
            });
            clientPublishTask.setConnectionCallback(new Callback<Void>() {
                @Override
                public void onSuccess(Void value) {
                }

                @Override
                public void onFailure(Throwable value) {
                    errorConnections.incrementAndGet();
                    for(int i=0; i<messageCount; i++) {
                        done.countDown(); // discount all client messages if any failure
                    }
                }
            });

            // add client to the queue
            queue.execute(clientPublishTask);
        }

        try {
            done.await();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
