package openspeak.probe;

import com.github.manevolent.ts3j.command.SingleCommand;
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter;
import com.github.manevolent.ts3j.event.TS3Listener;
import com.github.manevolent.ts3j.event.UnknownTeamspeakEvent;
import com.github.manevolent.ts3j.protocol.ProtocolRole;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Captures how the official TeamSpeak 6 client streams. Joins the server as "sniffer", asks to
 * join every stream it sees and writes every stream notification (the streamer's offer, its
 * signaling JSON, ...) to a log file. It also publishes a dummy stream "sniffer-test" so that an
 * official client can try to watch it, which captures the viewer side of the handshake.
 * gradlew :ts3j:runSniffer -Plog=capture.log
 */
public class StreamSniffer {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String logPath = System.getProperty("sniffer.log", "stream-capture.log");
        PrintWriter log = new PrintWriter(new FileWriter(logPath, true), true);

        LocalTeamspeakClientSocket c = Probe.connect(host, port, "sniffer", true);
        Set<String> joined = new HashSet<>();
        c.addListener(new TS3Listener() {
            @Override
            public void onUnknownEvent(UnknownTeamspeakEvent e) {
                String line = System.currentTimeMillis() + " " + e.getCommand() + " " + e.getMap();
                System.out.println(line);
                log.println(line);
                String id = e.getMap().get("id");
                String clid = e.getMap().get("clid");
                boolean announce = e.getCommand().equals("notifystreamstarted") || e.getCommand().equals("notifystreaminfo");
                if (announce && id != null && clid != null && !clid.equals(Integer.toString(c.getClientId())) && joined.add(id)) {
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            c.executeCommand(new SingleCommand("joinstreamrequest", ProtocolRole.CLIENT,
                                    new CommandSingleParameter("id", id),
                                    new CommandSingleParameter("clid", clid),
                                    new CommandSingleParameter("is_remove", "0"),
                                    new CommandSingleParameter("msg", ""))).get();
                            log.println("sent joinstreamrequest for " + id);
                        } catch (Exception ex) {
                            log.println("joinstreamrequest failed: " + ex);
                        }
                    }).start();
                }
            }
        });
        // Learn about streams that were already running before we connected.
        for (int clid = 1; clid < 64; clid++) {
            try {
                Thread.sleep(1100);
                c.executeCommand(new SingleCommand("requeststreaminfo", ProtocolRole.CLIENT,
                        new CommandSingleParameter("clid", Integer.toString(clid)))).get();
            } catch (Exception ignored) {
            }
            if (clid == 12) break;
        }
        c.executeCommand(new SingleCommand("setupstream", ProtocolRole.CLIENT,
                new CommandSingleParameter("name", "sniffer-test"),
                new CommandSingleParameter("type", "1"),
                new CommandSingleParameter("accessibility", "1"),
                new CommandSingleParameter("mode", "1"),
                new CommandSingleParameter("bitrate", "2500"),
                new CommandSingleParameter("viewer_limit", "0"),
                new CommandSingleParameter("audio", "0"))).get();
        log.println("READY " + System.currentTimeMillis());
        System.out.println("READY: start a screen share from the official client now");
        Thread.sleep(Long.MAX_VALUE);
    }
}
