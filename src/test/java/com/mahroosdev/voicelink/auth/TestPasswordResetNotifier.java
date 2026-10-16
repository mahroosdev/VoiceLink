package com.mahroosdev.voicelink.auth;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class TestPasswordResetNotifier implements PasswordResetNotifier {
    private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
    private final AtomicBoolean failNext = new AtomicBoolean();

    @Override
    public void send(String recipient, String resetUrl) {
        messages.add(new Message(recipient, resetUrl));
        if (failNext.getAndSet(false)) throw new PasswordResetMailException();
    }

    public Message await() throws InterruptedException {
        return messages.poll(5, TimeUnit.SECONDS);
    }

    public Message poll(long millis) throws InterruptedException {
        return messages.poll(millis, TimeUnit.MILLISECONDS);
    }

    public void clear() { messages.clear(); failNext.set(false); }
    public void failNext() { failNext.set(true); }

    public record Message(String recipient, String resetUrl) {
        public String token() { return resetUrl.substring(resetUrl.indexOf("#token=") + 7); }
    }
}
