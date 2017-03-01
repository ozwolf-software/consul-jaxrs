package net.ozwolf.consul.testutils;

import java.io.IOException;
import java.net.ServerSocket;

public class PortFactory {
    public static Integer getNextAvailable() {
        try {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
