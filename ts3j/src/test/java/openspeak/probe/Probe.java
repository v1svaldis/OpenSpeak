package openspeak.probe;

import com.github.manevolent.ts3j.audio.Microphone;
import com.github.manevolent.ts3j.command.ConnectionRejectedException;
import com.github.manevolent.ts3j.enums.CodecType;
import com.github.manevolent.ts3j.event.ChannelListEvent;
import com.github.manevolent.ts3j.event.ClientJoinEvent;
import com.github.manevolent.ts3j.event.TS3Listener;
import com.github.manevolent.ts3j.event.TextMessageEvent;
import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import com.github.manevolent.ts3j.util.Ts3Debugging;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Console check of the protocol stack against a real TeamSpeak 6 server, without an
 * Android device: connects two clients, prints the channel tree as the server pushes it,
 * sends a test tone from one client and verifies the other receives and decodes it.
 */
public class Probe {
    public static LocalTeamspeakClientSocket connect(String host, int port, String nick, boolean printTree) throws Exception {
        long t = System.currentTimeMillis();
        LocalIdentity identity = LocalIdentity.generateNew(8);
        // Round-trip through the TeamSpeak export format, as the app persists identities this way.
        LocalIdentity restored = LocalIdentity.read(new java.io.ByteArrayInputStream(identity.export().getBytes("UTF-8")));
        if (!restored.getUid().toBase64().equals(identity.getUid().toBase64())
                || restored.getKeyOffset() != identity.getKeyOffset())
            throw new IllegalStateException("identity export/read round trip changed the identity");
        LocalTeamspeakClientSocket client = new LocalTeamspeakClientSocket();
        client.setIdentity(restored);
        client.setNickname(nick);
        // Same signed version the Android app sends.
        client.setClientVersion("Android", "3.6.0 [Build: 1667819930]",
                "DMxIGKedSqo66DGSTd/vCa4bP0QQPP5TYXQcjML9KARTXD9cUmsSzWVSuZN5L4wQQRDcKJ2w9QWxHZP1KQrRCw==");
        client.addListener(new TS3Listener() {
            @Override
            public void onChannelList(ChannelListEvent e) {
                if (printTree)
                    System.out.println("  channel " + e.getChannelId() + " '" + e.getMap().get("channel_name")
                            + "' parent=" + e.getMap().get("cpid"));
            }

            @Override
            public void onClientJoin(ClientJoinEvent e) {
                if (printTree)
                    System.out.println("  client " + e.getClientId() + " '" + e.getClientNickname()
                            + "' -> channel " + e.getClientTargetId() + " platform=" + e.getMap().get("client_platform"));
            }

            @Override
            public void onTextMessage(TextMessageEvent e) {
                System.out.println("  text from " + e.getInvokerName() + ": " + e.getMessage());
            }
        });
        client.connect(new InetSocketAddress(host, port), null, 20000L);
        System.out.println(nick + " CONNECTED in " + (System.currentTimeMillis() - t) + " ms, clid=" + client.getClientId());
        return client;
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9987;
        Ts3Debugging.setEnabled(System.getenv("TS_DEBUG") != null);

        // A rejected connection must fail fast with the server's reason, not after the timeout.
        {
            LocalTeamspeakClientSocket bad = new LocalTeamspeakClientSocket();
            bad.setIdentity(LocalIdentity.generateNew(8));
            bad.setNickname("probe-bad-sign");
            bad.setClientVersion("Android", "3.6.0 [Build: 1667819930]", "AAAA");
            long t = System.currentTimeMillis();
            try {
                bad.connect(new InetSocketAddress(host, port), null, 20000L);
                System.out.println("UNEXPECTED: bad signature accepted");
            } catch (Exception e) {
                Throwable c = e;
                while (c.getCause() != null && !(c instanceof ConnectionRejectedException)) c = c.getCause();
                String detail = c instanceof ConnectionRejectedException
                        ? "id=0x" + Integer.toHexString(((ConnectionRejectedException) c).getErrorId()) + " msg=" + c.getMessage()
                        : c.toString();
                System.out.println("rejected in " + (System.currentTimeMillis() - t) + " ms: " + detail);
            }
            bad.close();
        }

        LocalTeamspeakClientSocket listener = connect(host, port, "probe-listener", true);
        AtomicInteger voicePackets = new AtomicInteger();
        AtomicLong decodedSamples = new AtomicLong();
        AtomicLong peak = new AtomicLong();
        OpusDecoder decoder = new OpusDecoder(48000, 1);
        short[] pcmOut = new short[5760];
        listener.setVoiceHandler(p -> {
            voicePackets.incrementAndGet();
            byte[] data = p.getCodecData();
            if (data.length == 0) return;
            try {
                int n = decoder.decode(data, 0, data.length, pcmOut, 0, 5760, false);
                decodedSamples.addAndGet(n);
                for (int i = 0; i < n; i++) peak.accumulateAndGet(Math.abs(pcmOut[i]), Math::max);
            } catch (Exception ex) {
                System.out.println("  decode error: " + ex);
            }
        });

        LocalTeamspeakClientSocket sender = connect(host, port, "probe-sender", false);
        try { sender.sendChannelMessage(1, "hello from openspeak probe"); } catch (Exception e) { System.out.println("  text send failed: " + e.getMessage()); }

        // 3 seconds of 440 Hz tone, 20 ms Opus frames
        OpusEncoder encoder = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(32000);
        ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();
        short[] pcm = new short[960];
        byte[] out = new byte[1275];
        long sample = 0;
        for (int f = 0; f < 150; f++) {
            for (int i = 0; i < pcm.length; i++, sample++)
                pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * sample / 48000.0) * 8000);
            int len = encoder.encode(pcm, 0, 960, out, 0, out.length);
            byte[] packet = new byte[len];
            System.arraycopy(out, 0, packet, 0, len);
            queue.add(packet);
        }
        sender.setMicrophone(new Microphone() {
            @Override public boolean isReady() { return !queue.isEmpty(); }
            @Override public CodecType getCodec() { return CodecType.OPUS_VOICE; }
            @Override public byte[] provide() {
                byte[] p = queue.poll();
                return p == null ? new byte[0] : p;
            }
        });

        Thread.sleep(5000);
        System.out.println("tone frames left unsent: " + queue.size()
                + ", voice packets received: " + voicePackets.get()
                + ", decoded seconds: " + decodedSamples.get() / 48000.0
                + ", peak amplitude: " + peak.get());
        sender.disconnect();
        listener.disconnect();
        System.out.println("DISCONNECTED");
        System.exit(voicePackets.get() > 100 ? 0 : 2);
    }
}
