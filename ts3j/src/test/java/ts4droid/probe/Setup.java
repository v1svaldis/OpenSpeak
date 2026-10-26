package ts4droid.probe;

import com.github.manevolent.ts3j.command.SingleCommand;
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter;
import com.github.manevolent.ts3j.event.ChannelCreateEvent;
import com.github.manevolent.ts3j.event.TS3Listener;
import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.protocol.ProtocolRole;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import com.github.manevolent.ts3j.util.Ts3Crypt;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Prepares a local test server for the app's end-to-end checks: redeems the server-admin
 * privilege key and creates "Room A" (with sub-channel "Sub"), and "Room B" with password 123.
 * gradlew :ts3j:runSetup -Ptoken=...
 */
public class Setup {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String token = args[2];

        LocalTeamspeakClientSocket client = new LocalTeamspeakClientSocket();
        client.setIdentity(LocalIdentity.generateNew(8));
        client.setNickname("setup");
        client.setClientVersion("Android", "3.6.0 [Build: 1667819930]",
                "DMxIGKedSqo66DGSTd/vCa4bP0QQPP5TYXQcjML9KARTXD9cUmsSzWVSuZN5L4wQQRDcKJ2w9QWxHZP1KQrRCw==");
        client.setOption("client.default_token", token);
        CompletableFuture<Integer>[] created = new CompletableFuture[]{new CompletableFuture<>()};
        client.addListener(new TS3Listener() {
            @Override public void onChannelCreate(ChannelCreateEvent e) {
                created[0].complete(e.getChannelId());
            }
        });
        client.connect(new InetSocketAddress(host, port), null, 20000L);
        System.out.println("connected as admin candidate, clid=" + client.getClientId());

        int roomA = create(client, created, new CommandSingleParameter("channel_name", "Room A"));
        System.out.println("Room A = " + roomA);
        int sub = create(client, created, new CommandSingleParameter("channel_name", "Sub"),
                new CommandSingleParameter("cpid", Integer.toString(roomA)));
        System.out.println("Sub = " + sub);
        int roomB = create(client, created, new CommandSingleParameter("channel_name", "Room B"),
                new CommandSingleParameter("channel_password", Ts3Crypt.hashPassword("123")),
                new CommandSingleParameter("channel_order", Integer.toString(roomA)));
        System.out.println("Room B = " + roomB);

        client.disconnect();
        System.exit(0);
    }

    private static int create(LocalTeamspeakClientSocket client, CompletableFuture<Integer>[] created,
                              CommandSingleParameter... params) throws Exception {
        created[0] = new CompletableFuture<>();
        SingleCommand cmd = new SingleCommand("channelcreate", ProtocolRole.CLIENT);
        for (CommandSingleParameter p : params) cmd.add(p);
        cmd.add(new CommandSingleParameter("channel_flag_permanent", "1"));
        client.executeCommand(cmd).get();
        return created[0].get(5, TimeUnit.SECONDS);
    }
}
