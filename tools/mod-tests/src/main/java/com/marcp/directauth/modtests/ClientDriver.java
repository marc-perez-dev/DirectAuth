package com.marcp.directauth.modtests;

import com.marcp.directauth.modtests.scenarios.Scenario;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Conduce un cliente real durante un escenario. El escenario corre en su propio hilo y espera
 * condiciones; todo lo que toca el juego pasa por {@link #onClient}, que lo ejecuta en el hilo
 * del cliente. Cada comprobación se escribe como una línea JSON en el fichero de resultados.
 */
public final class ClientDriver {
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final List<String> chat = new CopyOnWriteArrayList<>();

    private final Minecraft mc = Minecraft.getInstance();
    private final Path out;
    private final String server;
    private final Rcon rcon;
    private int chatMark;
    private int failures;

    private ClientDriver(Path out, String server, Rcon rcon) {
        this.out = out;
        this.server = server;
        this.rcon = rcon;
    }

    static void install() {
        // Sin códigos de color: DirectAuth escribe sus mensajes con secuencias §
        MinecraftForge.EVENT_BUS.addListener((ClientChatReceivedEvent event) -> chat.add(event.getMessage().getString().replaceAll("§.", "")));
        // El escenario empieza cuando el juego ha terminado de cargar (primera pantalla de título)
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Opening event) -> {
            if (event.getNewScreen() instanceof TitleScreen && started.compareAndSet(false, true)) {
                Thread thread = new Thread(ClientDriver::runScenario, "mod-test scenario");
                thread.setDaemon(true);
                thread.start();
            }
        });
    }

    private static void runScenario() {
        String name = System.getProperty("directauth.modtest.scenario", "baseline");
        Path out = Path.of(System.getProperty("directauth.modtest.out", "modtest-results.jsonl"));
        String server = System.getProperty("directauth.modtest.server", "127.0.0.1:25570");
        int rconPort = Integer.getInteger("directauth.modtest.rcon", 25580);
        ClientDriver driver = null;
        try {
            Files.deleteIfExists(out);
            driver = new ClientDriver(out, server, new Rcon("127.0.0.1", rconPort, "modtests"));
            Scenario scenario = (Scenario) Class.forName("com.marcp.directauth.modtests.scenarios." + className(name))
                .getDeclaredConstructor().newInstance();
            scenario.run(driver);
            driver.record("scenario finished", driver.failures == 0, driver.failures + " failed check(s)");
        } catch (Throwable t) {
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            if (driver != null) {
                driver.record("scenario error", false, trace.toString());
            } else {
                appendLine(out, json("scenario error", false, trace.toString()));
            }
        } finally {
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
        }
    }

    /** "skyblock-builder" -> "SkyblockBuilder" */
    private static String className(String scenario) {
        StringBuilder b = new StringBuilder();
        for (String part : scenario.split("[-_]")) {
            if (!part.isEmpty()) b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    // ---------- Resultados ----------

    /** Comprobación que no detiene el escenario: anota PASS/FAIL y sigue. */
    public void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        record(name, ok, detail);
    }

    /** Condición sin la que el resto del escenario no tiene sentido. */
    public void require(String name, boolean ok, String detail) {
        check(name, ok, detail);
        if (!ok) throw new IllegalStateException("required check failed: " + name + " — " + detail);
    }

    private void record(String name, boolean ok, String detail) {
        appendLine(out, json(name, ok, detail));
    }

    private static void appendLine(Path out, String line) {
        try {
            Files.writeString(out, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String json(String name, boolean ok, String detail) {
        return "{\"check\":" + quote(name) + ",\"ok\":" + ok + ",\"detail\":" + quote(detail == null ? "" : detail) + "}";
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return b.append('"').toString();
    }

    // ---------- Juego ----------

    public Minecraft mc() {
        return mc;
    }

    /** Ejecuta en el hilo del cliente y espera el resultado. */
    public <T> T onClient(Supplier<T> action) throws Exception {
        return CompletableFuture.supplyAsync(action, mc).get(20, TimeUnit.SECONDS);
    }

    public void onClientRun(Runnable action) throws Exception {
        onClient(() -> { action.run(); return null; });
    }

    public String rcon(String command) throws IOException {
        return rcon.command(command);
    }

    public void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** Espera a que se cumpla una condición evaluada en el hilo del cliente. */
    public boolean waitFor(BooleanSupplier condition, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (onClient(condition::getAsBoolean)) return true;
            Thread.sleep(100);
        }
        return false;
    }

    /** Conecta al servidor de pruebas y espera a estar en el mundo. */
    public void connect() throws Exception {
        markChat();
        onClientRun(() -> {
            ServerAddress address = ServerAddress.parseString(server);
            ConnectScreen.startConnecting(new TitleScreen(), mc, address,
                new ServerData("mod-test", server, false), false);
        });
        boolean joined = waitFor(() -> mc.player != null && mc.level != null, 120_000);
        require("joined the server", joined, "screen: " + screenName() + ", chat: " + chatSinceMark());
    }

    public void disconnect() throws Exception {
        onClientRun(() -> {
            if (mc.level != null) mc.level.disconnect();
            mc.clearLevel(new TitleScreen());
        });
        require("disconnected", waitFor(() -> mc.level == null, 30_000), "screen: " + screenName());
    }

    public boolean connected() throws Exception {
        return onClient(() -> mc.getConnection() != null && mc.level != null);
    }

    /** Envía un comando como el jugador (sin la barra inicial). */
    public void command(String command) throws Exception {
        markChat();
        onClientRun(() -> mc.player.connection.sendCommand(command));
    }

    /** Envía un paquete de otra mod como lo haría su cliente: su canal y el contenido ya codificado. */
    public void sendToServer(ResourceLocation channel, FriendlyByteBuf data) throws Exception {
        onClientRun(() -> mc.getConnection().send(new ServerboundCustomPayloadPacket(channel, data)));
    }

    public void markChat() {
        chatMark = chat.size();
    }

    /** Espera un mensaje de chat recibido desde la última marca. */
    public String waitChat(String regex, long timeoutMs) throws Exception {
        Pattern pattern = Pattern.compile(regex);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            for (int i = chatMark; i < chat.size(); i++) {
                if (pattern.matcher(chat.get(i)).find()) return chat.get(i);
            }
            Thread.sleep(100);
        }
        return null;
    }

    public List<String> chatSinceMark() {
        return List.copyOf(chat.subList(Math.min(chatMark, chat.size()), chat.size()));
    }

    /** Clase de la pantalla abierta ("none" si no hay). */
    public String screenName() throws Exception {
        return onClient(() -> mc.screen == null ? "none" : mc.screen.getClass().getName());
    }
}
