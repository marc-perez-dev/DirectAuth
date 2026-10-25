package com.marcp.directauth.modtests;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Cliente RCON mínimo (protocolo de Source) para preparar y comprobar el servidor de pruebas. */
public final class Rcon implements AutoCloseable {
    private static final int LOGIN = 3, COMMAND = 2;
    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    public Rcon(String host, int port, String password) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(30_000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        int id = send(LOGIN, password);
        if (readPacket().id != id) throw new IOException("RCON login rejected");
    }

    /**
     * El servidor parte las respuestas en trozos de 4096 bytes con el mismo id: mientras llega un
     * trozo lleno puede venir otro. No se puede enviar nada más detrás del comando: el servidor lee
     * cada paquete de una vez y cierra la conexión si en esa lectura llegan dos.
     */
    public synchronized String command(String command) throws IOException {
        int id = send(COMMAND, command);
        StringBuilder body = new StringBuilder();
        Packet p = readPacket();
        for (;;) {
            if (p.id != id) throw new IOException("RCON reply out of order");
            body.append(p.body);
            if (p.body.getBytes(StandardCharsets.UTF_8).length < 4096) break;
            socket.setSoTimeout(500); // respuesta de exactamente 4096 bytes: no viene nada más
            try {
                p = readPacket();
            } catch (SocketTimeoutException e) {
                break;
            } finally {
                socket.setSoTimeout(30_000);
            }
        }
        return body.toString().replaceAll("§.", "");
    }

    private int send(int type, String body) throws IOException {
        int id = nextId++;
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(10 + payload.length).putInt(id).putInt(type).put(payload).put((byte) 0).put((byte) 0);
        out.write(buf.array());
        out.flush();
        return id;
    }

    private record Packet(int id, String body) {}

    private Packet readPacket() throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] rest = new byte[length];
        in.readFully(rest);
        ByteBuffer buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = buf.getInt();
        buf.getInt(); // tipo
        String body = new String(rest, 8, Math.max(0, length - 10), StandardCharsets.UTF_8);
        return new Packet(id, body);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
