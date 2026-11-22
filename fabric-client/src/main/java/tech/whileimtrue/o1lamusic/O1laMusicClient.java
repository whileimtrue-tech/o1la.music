package tech.whileimtrue.o1lamusic;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.mojang.brigadier.arguments.StringArgumentType;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

public final class O1laMusicClient implements ClientModInitializer {
    public static final RelayClient RELAY = new RelayClient();
    @Override public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("c418music")
                .then(literal("connect")
                    .then(argument("url", StringArgumentType.string())
                        .then(argument("room", StringArgumentType.word())
                            .executes(ctx -> {
                                String url=StringArgumentType.getString(ctx,"url");
                                String room=StringArgumentType.getString(ctx,"room");
                                RELAY.connect(url,room, message -> Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[o1la.music] "+message))));
                                ctx.getSource().sendFeedback(Component.literal("Connecting to "+url+" room "+room));
                                return 1;
                            }))))
                .then(literal("disconnect").executes(ctx->{RELAY.disconnect();ctx.getSource().sendFeedback(Component.literal("Disconnected"));return 1;}))
                .then(literal("status").executes(ctx->{ctx.getSource().sendFeedback(Component.literal(RELAY.status()));return 1;}))
            )
        );
    }
}
