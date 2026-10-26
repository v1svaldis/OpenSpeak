package com.github.manevolent.ts3j.protocol.packet.handler.client;

import com.github.manevolent.ts3j.command.CommandProcessException;
import com.github.manevolent.ts3j.command.CommandProcessor;
import com.github.manevolent.ts3j.command.ConnectionRejectedException;
import com.github.manevolent.ts3j.command.SingleCommand;
import com.github.manevolent.ts3j.command.parameter.CommandParameter;
import com.github.manevolent.ts3j.command.MultiCommand;
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;


public class LocalClientHandlerRetrievingData extends LocalClientHandlerFull {
    public LocalClientHandlerRetrievingData(LocalTeamspeakClientSocket client) {
        super(client);
    }

    @Override
    protected void handleCommand(MultiCommand command) throws CommandProcessException {
        if (command.getName().equalsIgnoreCase("error")) {
            SingleCommand error = command.simplifyOne();
            CommandParameter id = error.get("id");
            CommandParameter extra = error.get("extra_msg");
            ConnectionRejectedException rejection = new ConnectionRejectedException(
                    id != null ? Integer.parseInt(id.getValue()) : -1,
                    error.get("msg").getValue(),
                    extra != null ? extra.getValue() : null
            );
            getClient().failConnect(rejection);

            try {
                getClient().setState(ClientConnectionState.DISCONNECTED);
            } catch (Exception e) {
                getClient().getExceptionHandler().accept(e);
            }

            throw rejection;
        }

        CommandProcessor processor = getClient().getCommandProcessor();
        if (processor != null) processor.process(getClient(), command);
    }

}
