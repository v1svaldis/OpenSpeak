package ts4droid.probe;

import com.github.manevolent.ts3j.event.ClientJoinEvent;
import com.github.manevolent.ts3j.event.ClientLeaveEvent;
import com.github.manevolent.ts3j.event.ClientMovedEvent;
import com.github.manevolent.ts3j.event.ClientUpdatedEvent;
import com.github.manevolent.ts3j.event.TS3Listener;
import com.github.manevolent.ts3j.enums.CodecType;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A second participant for testing the Android app end to end: stays on the server, says a
 * 440 Hz tone for 2 s every 6 s, and logs who joins, moves, mutes and how much voice it hears.
 * gradlew :ts3j:runCompanion -Phost=127.0.0.1 -Pseconds=120
 */
public class Companion {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9987;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 120;

        LocalTeamspeakClientSocket client = Probe.connect(host, port, "companion", true);
        Map<Integer, AtomicInteger> heard = new ConcurrentHashMap<>();
        client.setVoiceHandler(p -> heard.computeIfAbsent(p.getClientId(), k -> new AtomicInteger()).incrementAndGet());
        client.addListener(new TS3Listener() {
            @Override public void onClientMoved(ClientMovedEvent e) {
                System.out.println("  moved: client " + e.getClientId() + " -> channel " + e.getTargetChannelId());
            }
            @Override public void onClientLeave(ClientLeaveEvent e) {
                System.out.println("  left: client " + e.getClientId());
            }
            @Override public void onClientChanged(ClientUpdatedEvent e) {
                System.out.println("  updated: client " + e.getClientId() + " " + e.getMap());
            }
        });

        OpusEncoder encoder = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] pcm = new short[960];
        byte[] out = new byte[1275];
        long sample = 0;
        long end = System.currentTimeMillis() + seconds * 1000L;
        int tick = 0;
        while (System.currentTimeMillis() < end) {
            long frameStart = System.nanoTime();
            if (tick % 300 < 100) { // 2 s of every 6 s
                for (int i = 0; i < pcm.length; i++, sample++)
                    pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * sample / 48000.0) * 8000);
                int len = encoder.encode(pcm, 0, 960, out, 0, out.length);
                byte[] packet = new byte[len];
                System.arraycopy(out, 0, packet, 0, len);
                client.sendVoice(CodecType.OPUS_VOICE, packet);
            } else if (tick % 300 == 100) {
                client.sendVoiceEnd(CodecType.OPUS_VOICE);
            }
            if (tick % 250 == 0) System.out.println("  voice packets heard per client: " + heard);
            tick++;
            long sleep = 20_000_000L - (System.nanoTime() - frameStart);
            if (sleep > 0) Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
        }
        System.out.println("FINAL voice packets heard per client: " + heard);
        client.disconnect();
        System.exit(0);
    }
}
