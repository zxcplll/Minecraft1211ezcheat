package io.github.zxcplll.minecraft1211ezcheat;

import com.sun.tools.attach.VirtualMachine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Attacher {
    private Attacher() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 6) {
            System.err.println("Usage: Attacher <pid> <agent.jar> <hooks.jar> <settings.json> <status.json> <overlay.dll>");
            System.exit(2);
        }

        String payload = String.join("\n", arguments[2], arguments[3], arguments[4], arguments[5]);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        VirtualMachine machine = VirtualMachine.attach(arguments[0]);
        try {
            machine.loadAgent(arguments[1], encoded);
        } finally {
            machine.detach();
        }
        System.out.println("ATTACHED");
    }
}
