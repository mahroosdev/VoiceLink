package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomParticipantRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.room.RoomStatus;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "voicelink.standard.free-tier-confirmed=false")
@ActiveProfiles("test")
class RoomLiveWebSocketTests {
    private static final String PASSWORD = "test-password-123";
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*>");
    private static final Pattern VALUE = Pattern.compile("value=\"([^\"]+)\"");

    @Value("${local.server.port}") int port;
    @Autowired UserAccountRepository accounts;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired RoomParticipantRepository participants;
    @Autowired RoomService rooms;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    private UserAccount account(String label) {
        return accounts.save(new UserAccount(label, UUID.randomUUID() + "@example.test",
                encoder.encode(PASSWORD)));
    }

    private Browser browser(UserAccount account) throws Exception {
        Browser browser = new Browser(port, json);
        browser.login(account.getEmail());
        return browser;
    }

    @Test
    void speechUploadPublishesSafeTurnEventsOverRealSocketWithoutProviderCredentials() throws Exception {
        var a = account("Speech A");
        var b = account("Speech B");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        Browser first = browser(a);
        Browser second = browser(b);
        LiveSocket aSocket = first.connect(roomId, first.base);
        LiveSocket bSocket = second.connect(roomId, second.base);
        aSocket.await("ROOM_STATE");
        bSocket.await("ROOM_STATE");
        UUID requestId = UUID.randomUUID();
        assertThat(first.upload(roomId, requestId, smallWebm())).isEqualTo(202);
        JsonNode acceptedA = aSocket.await("TURN_ACCEPTED");
        JsonNode acceptedB = bSocket.await("TURN_ACCEPTED");
        assertThat(acceptedA.path("eventId").asText()).isEqualTo(acceptedB.path("eventId").asText());
        assertThat(acceptedA.path("payload").path("sourceLanguage").asText()).isEqualTo("en");
        assertThat(acceptedA.path("payload").path("targetLanguage").asText()).isEqualTo("ta");
        JsonNode failed = bSocket.await("TURN_FAILED");
        assertThat(failed.path("payload").path("stage").asText()).isEqualTo("STT");
        assertThat(failed.path("payload").path("code").asText()).isEqualTo("CONFIGURATION");
        assertThat(failed.path("sequence").longValue()).isGreaterThan(acceptedB.path("sequence").longValue());
        assertThat(failed.toString()).doesNotContain("replace-with", "Bearer", "api.groq.com");
    }

    private static byte[] smallWebm() {
        byte[] bytes = new byte[64];
        bytes[0] = 0x1a; bytes[1] = 0x45; bytes[2] = (byte) 0xdf; bytes[3] = (byte) 0xa3;
        byte[] marker = "webmA_OPUSOpusHead".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, bytes, 8, marker.length);
        return bytes;
    }

    @Test
    void waitingActivationAndBidirectionalDeliveryAreRealWebSocketEvents() throws Exception {
        var a = account("A");
        var b = account("B");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        Browser first = browser(a);
        Browser second = browser(b);
        LiveSocket aSocket = first.connect(roomId, first.base);
        assertThat(aSocket.await("ROOM_STATE").path("payload").path("status").textValue())
                .isEqualTo("WAITING");
        aSocket.sendText("too soon");
        assertThat(aSocket.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("ROOM_NOT_ACTIVE");
        new TransactionTemplate(transactions).execute(ignored -> {
            rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
            try {
                assertThat(aSocket.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return null;
        });
        assertThat(aSocket.await("ROOM_STATE").path("payload").path("status").textValue())
                .isEqualTo("ACTIVE");
        LiveSocket bSocket = second.connect(roomId, second.base);
        assertThat(bSocket.await("ROOM_STATE").path("payload").path("status").textValue())
                .isEqualTo("ACTIVE");
        aSocket.sendText("hello B");
        JsonNode aEcho = aSocket.await("TEXT_MESSAGE");
        JsonNode bReceive = bSocket.await("TEXT_MESSAGE");
        UUID aParticipant = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).getFirst().getId();
        assertThat(bReceive.path("payload").path("text").textValue()).isEqualTo("hello B");
        assertThat(bReceive.path("senderParticipantId").textValue()).isEqualTo(aParticipant.toString());
        assertThat(aEcho.path("eventId").textValue()).isEqualTo(bReceive.path("eventId").textValue());
        bSocket.sendText("hello A");
        JsonNode aReceive = aSocket.await("TEXT_MESSAGE");
        JsonNode bEcho = bSocket.await("TEXT_MESSAGE");
        assertThat(aReceive.path("payload").path("text").textValue()).isEqualTo("hello A");
        assertThat(aReceive.path("sequence").longValue()).isGreaterThan(aEcho.path("sequence").longValue());
        assertThat(bEcho.path("eventId").textValue()).isEqualTo(aReceive.path("eventId").textValue());
        new TransactionTemplate(transactions).execute(ignored -> {
            rooms.closeRoom(b.getId(), roomId);
            try {
                assertThat(aSocket.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return null;
        });
        JsonNode closedA = aSocket.await("ROOM_CLOSED");
        JsonNode closedB = bSocket.await("ROOM_CLOSED");
        assertThat(closedA.path("sequence").longValue()).isGreaterThan(aReceive.path("sequence").longValue());
        assertThat(closedA.path("eventId").textValue()).isEqualTo(closedB.path("eventId").textValue());
        assertThat(roomRepository.findById(roomId).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
    }

    @Test
    void handshakeRejectsAnonymousNonMemberClosedAndWrongOrigin() throws Exception {
        var a = account("A");
        var outsider = account("Outsider");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        Browser anonymous = new Browser(port, json);
        Browser member = browser(a);
        Browser nonMember = browser(outsider);
        assertThat(member.http.send(HttpRequest.newBuilder(URI.create(member.base + "/js/room-live.js"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(anonymous.http.send(HttpRequest.newBuilder(URI.create(anonymous.base + "/js/room-live.js"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(302);
        assertThatThrownBy(() -> anonymous.connect(roomId, anonymous.base)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> nonMember.connect(roomId, nonMember.base)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> member.connect(UUID.randomUUID(), member.base)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> member.connect(roomId, "https://wrong.example")).isInstanceOf(Exception.class);
        rooms.closeRoom(a.getId(), roomId);
        assertThatThrownBy(() -> member.connect(roomId, member.base)).isInstanceOf(Exception.class);
    }

    @Test
    void validationReplacementAndLogoutRevocation() throws Exception {
        var a = account("A");
        var b = account("B");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        Browser first = browser(a);
        Browser second = browser(b);
        LiveSocket old = first.connect(roomId, first.base);
        old.await("ROOM_STATE");
        LiveSocket replacement = first.connect(roomId, first.base);
        replacement.await("ROOM_STATE");
        old.awaitClose();
        assertThat(old.closeCode).isEqualTo(4001);
        LiveSocket peer = second.connect(roomId, second.base);
        peer.await("ROOM_STATE");
        peer.sendText("after replacement");
        assertThat(replacement.await("TEXT_MESSAGE").path("payload").path("text").textValue())
                .isEqualTo("after replacement");
        peer.await("TEXT_MESSAGE");

        replacement.sendRaw("{");
        assertThat(replacement.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("INVALID_MESSAGE");
        replacement.sendText("   ");
        assertThat(replacement.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("INVALID_MESSAGE");
        replacement.sendText("x".repeat(1001));
        assertThat(replacement.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("INVALID_MESSAGE");
        replacement.sendRaw("{\"protocolVersion\":1,\"type\":\"SEND_TEXT\",\"clientMessageId\":\""
                + UUID.randomUUID() + "\",\"text\":\"spoof\",\"senderParticipantId\":\""
                + UUID.randomUUID() + "\"}");
        assertThat(replacement.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("INVALID_MESSAGE");
        first.logout(roomId);
        replacement.awaitClose();
        assertThat(roomRepository.findById(roomId).orElseThrow().getStatus()).isEqualTo(RoomStatus.ACTIVE);
        assertThatThrownBy(() -> first.connect(roomId, first.base)).isInstanceOf(Exception.class);
        LiveSocket renewed = browser(a).connect(roomId, first.base);
        assertThat(renewed.await("ROOM_STATE").path("payload").path("status").textValue())
                .isEqualTo("ACTIVE");
        renewed.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        LiveSocket binary = browser(a).connect(roomId, first.base);
        binary.await("ROOM_STATE");
        binary.socket.sendBinary(java.nio.ByteBuffer.wrap(new byte[] {1}), true).join();
        binary.awaitClose();
    }

    @Test
    void separateRoomsNeverReceiveEachOthersTextAndDuplicateIdsAreIgnored() throws Exception {
        var a = account("A");
        var b = account("B");
        var c = account("C");
        var d = account("D");
        UUID firstRoom = rooms.createRoom(a.getId(), "en", "ta");
        UUID secondRoom = rooms.createRoom(c.getId(), "ta", "en");
        rooms.joinRoom(b.getId(), roomRepository.findById(firstRoom).orElseThrow().getJoinCode());
        rooms.joinRoom(d.getId(), roomRepository.findById(secondRoom).orElseThrow().getJoinCode());
        LiveSocket aSocket = browser(a).connect(firstRoom, "http://localhost:" + port);
        LiveSocket bSocket = browser(b).connect(firstRoom, "http://localhost:" + port);
        LiveSocket cSocket = browser(c).connect(secondRoom, "http://localhost:" + port);
        LiveSocket dSocket = browser(d).connect(secondRoom, "http://localhost:" + port);
        aSocket.await("ROOM_STATE");
        bSocket.await("ROOM_STATE");
        cSocket.await("ROOM_STATE");
        dSocket.await("ROOM_STATE");
        UUID id = UUID.randomUUID();
        String request = "{\"protocolVersion\":1,\"type\":\"SEND_TEXT\",\"clientMessageId\":\""
                + id + "\",\"text\":\"room one\"}";
        aSocket.sendRaw(request);
        aSocket.sendRaw(request);
        assertThat(bSocket.await("TEXT_MESSAGE").path("payload").path("text").textValue())
                .isEqualTo("room one");
        assertThat(bSocket.messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(cSocket.messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(dSocket.messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        rooms.closeRoom(a.getId(), firstRoom);
        bSocket.await("ROOM_CLOSED");
        assertThat(roomRepository.findById(secondRoom).orElseThrow().getStatus()).isEqualTo(RoomStatus.ACTIVE);
    }

    @Test
    void concurrentSendsShareOneOrderAndClosedEndsTheStream() throws Exception {
        var a = account("A");
        var b = account("B");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        LiveSocket aSocket = browser(a).connect(roomId, "http://localhost:" + port);
        LiveSocket bSocket = browser(b).connect(roomId, "http://localhost:" + port);
        aSocket.await("ROOM_STATE");
        bSocket.await("ROOM_STATE");
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 5; i++) aSocket.sendText("A" + i);
                return null;
            });
            var second = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 5; i++) bSocket.sendText("B" + i);
                return null;
            });
            start.countDown();
            first.get(8, TimeUnit.SECONDS);
            second.get(8, TimeUnit.SECONDS);
            List<JsonNode> aEvents = new ArrayList<>();
            List<JsonNode> bEvents = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                aEvents.add(aSocket.await("TEXT_MESSAGE"));
                bEvents.add(bSocket.await("TEXT_MESSAGE"));
            }
            assertThat(aEvents.stream().map(e -> e.path("eventId").textValue()).toList())
                    .containsExactlyElementsOf(
                            bEvents.stream().map(e -> e.path("eventId").textValue()).toList());
            for (int i = 1; i < aEvents.size(); i++) {
                assertThat(aEvents.get(i).path("sequence").longValue())
                        .isGreaterThan(aEvents.get(i - 1).path("sequence").longValue());
            }
            CountDownLatch race = new CountDownLatch(1);
            var sender = workers.submit(() -> {
                race.await();
                try { aSocket.sendText("racing close"); } catch (RuntimeException ignored) {}
                return null;
            });
            var closer = workers.submit(() -> {
                race.await();
                rooms.closeRoom(b.getId(), roomId);
                return null;
            });
            race.countDown();
            sender.get(8, TimeUnit.SECONDS);
            closer.get(8, TimeUnit.SECONDS);
            JsonNode closedA = aSocket.await("ROOM_CLOSED");
            JsonNode closedB = bSocket.await("ROOM_CLOSED");
            assertThat(closedA.path("eventId").textValue()).isEqualTo(closedB.path("eventId").textValue());
            assertThat(aSocket.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
            assertThat(bSocket.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    private static final class Browser {
        final int port;
        final String base;
        final HttpClient http;
        final ObjectMapper json;

        Browser(int port, ObjectMapper json) {
            this.port = port;
            this.base = "http://localhost:" + port;
            this.http = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .connectTimeout(Duration.ofSeconds(5)).build();
            this.json = json;
        }

        void login(String email) throws Exception {
            String token = csrf("/login");
            String form = "email=" + encode(email) + "&password=" + encode(PASSWORD)
                    + "&_csrf=" + encode(token);
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("location").orElse("")).endsWith("/app");
        }

        void logout(UUID roomId) throws Exception {
            String token = csrf("/rooms/" + roomId);
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/logout"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(token))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
        }

        String csrf(String path) throws Exception {
            var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var input = CSRF_INPUT.matcher(response.body());
            assertThat(input.find()).isTrue();
            var value = VALUE.matcher(input.group());
            assertThat(value.find()).isTrue();
            return value.group(1);
        }

        LiveSocket connect(UUID roomId, String origin) throws Exception {
            LiveSocket listener = new LiveSocket(json);
            listener.socket = http.newWebSocketBuilder().header("Origin", origin)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/rooms/" + roomId), listener)
                    .get(10, TimeUnit.SECONDS);
            return listener;
        }

        int upload(UUID roomId, UUID requestId, byte[] audio) throws Exception {
            String boundary = "test-" + UUID.randomUUID();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            multipartField(body, boundary, "clientRequestId", requestId.toString());
            multipartField(body, boundary, "durationMillis", "1000");
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"turn.webm\"\r\n"
                    + "Content-Type: audio/webm;codecs=opus\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(audio);
            body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/rooms/" + roomId + "/turns"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-CSRF-TOKEN", csrf("/rooms/" + roomId))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        }

        private static void multipartField(java.io.ByteArrayOutputStream body, String boundary,
                                           String name, String value) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                    + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private static final class LiveSocket implements WebSocket.Listener {
        final ObjectMapper json;
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final java.util.concurrent.CompletableFuture<Void> closed = new java.util.concurrent.CompletableFuture<>();
        final StringBuilder current = new StringBuilder();
        WebSocket socket;
        volatile int closeCode;

        LiveSocket(ObjectMapper json) { this.json = json; }

        @Override
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                try { messages.add(json.readTree(current.toString())); }
                catch (Exception ex) { closed.completeExceptionally(ex); }
                current.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }

        JsonNode await(String type) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < until) {
                JsonNode event = messages.poll(1, TimeUnit.SECONDS);
                if (event != null && type.equals(event.path("type").textValue())) return event;
            }
            throw new AssertionError("Timed out waiting for " + type);
        }

        void awaitClose() throws Exception { closed.get(8, TimeUnit.SECONDS); }

        void sendText(String value) {
            sendRaw("{\"protocolVersion\":1,\"type\":\"SEND_TEXT\",\"clientMessageId\":\""
                    + UUID.randomUUID() + "\",\"text\":" + json.writeValueAsString(value) + "}");
        }

        void sendRaw(String value) { socket.sendText(value, true).join(); }
    }
}
