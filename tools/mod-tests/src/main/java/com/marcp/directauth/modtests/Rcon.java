package com.marcp.directauth.modtests;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Cliente RCON mínimo (protocolo de Source) para preparar y comprobar el servidor de pruebas. */
public final class Rcon implements AutoCloseable {
    private static final int LOGIN = 3, COMMAND = 2;
    /** Tamaño máximo de cada trozo de una respuesta. */
    private static final int CHUNK = 4096;
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
     * El servidor parte las respuestas en trozos de 4096 bytes con el mismo id. Si el primero llega
     * lleno puede haber más: entonces se envía un paquete vacío y todo lo que llega antes de su
     * respuesta es del comando. No se envía antes porque el servidor lee cada paquete de una vez y
     * cierra la conexión si en esa lectura llegan dos.
     */
    public synchronized String command(String command) throws IOException {
        int id = send(COMMAND, command);
        Packet p = readPacket();
        if (p.id != id) throw new IOException("RCON reply out of order");
        StringBuilder body = new StringBuilder(p.body);
        if (p.size >= CHUNK) {
            int end = send(COMMAND, "");
            for (p = readPacket(); p.id != end; p = readPacket()) {
                if (p.id != id) throw new IOException("RCON reply out of order");
                body.append(p.body);
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

    /** {@code size}: bytes del cuerpo tal como llegaron. */
    private record Packet(int id, String body, int size) {}

    private Packet readPacket() throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] rest = new byte[length];
        in.readFully(rest);
        ByteBuffer buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = buf.getInt();
        buf.getInt(); // tipo
        int size = Math.max(0, length - 10);
        return new Packet(id, new String(rest, 8, size, StandardCharsets.UTF_8), size);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
