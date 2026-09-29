package com.java_template.testing.cyoda;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

final class FreePorts {

    private FreePorts() {
    }

    static int[] allocate(int count) {
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(0);
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot allocate free ports", e);
        } finally {
            for (ServerSocket s : sockets) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
    }
}
