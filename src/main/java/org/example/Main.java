package org.example;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Base64;

public class Main {

    private static final String BASE_URL = "http://localhost:8080/chat/server";

    private static Client client;
    private static Scanner scanner;

    private static String currentLogin = null;
    private static String currentPassword = null;

    private static volatile boolean running = true;
    private static Thread backgroundThread;

    private static Set<String> knownUsers = new HashSet<>();

    public static void main(String[] args) {
        client = ClientBuilder.newClient();
        scanner = new Scanner(System.in);

        System.out.println("REST chat client started.");
        printHelp();

        while (running) {
            try {
                System.out.print("> ");
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                if (input.equalsIgnoreCase("ping")) {
                    doPing();
                } else if (input.startsWith("echo ")) {
                    doEcho(input.substring(5));
                } else if (input.startsWith("login ")) {
                    handleLoginCommand(input);
                } else if (input.equalsIgnoreCase("list")) {
                    doListUsers();
                } else if (input.startsWith("msg ")) {
                    handleMessageCommand(input);
                } else if (input.startsWith("file ")) {
                    handleFileCommand(input);
                } else if (input.equalsIgnoreCase("drain")) {
                    drainAllNow();
                } else if (input.equalsIgnoreCase("help")) {
                    printHelp();
                } else if (input.equalsIgnoreCase("exit")) {
                    doExit();
                } else {
                    System.out.println("Unknown command. Type 'help' to see available commands.");
                }

            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private static void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  ping");
        System.out.println("  echo <text>");
        System.out.println("  login <username> <password>");
        System.out.println("  list");
        System.out.println("  msg <username> <message>");
        System.out.println("  file <username> <full_path_to_file>");
        System.out.println("  drain");
        System.out.println("  help");
        System.out.println("  exit");
    }

    private static void doPing() {
        try {
            String response = client
                    .target(BASE_URL + "/ping")
                    .request(MediaType.TEXT_PLAIN)
                    .get(String.class);

            System.out.println("Server response: " + response);
        } catch (Exception e) {
            System.out.println("Ping failed: " + e.getMessage());
        }
    }

    private static void doEcho(String text) {
        try {
            String response = client
                    .target(BASE_URL + "/echo")
                    .request(MediaType.TEXT_PLAIN)
                    .post(Entity.text(text), String.class);

            System.out.println("Server response: " + response);
        } catch (Exception e) {
            System.out.println("Echo failed: " + e.getMessage());
        }
    }

    private static void handleLoginCommand(String input) {
        String[] parts = input.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("Usage: login <username> <password>");
            return;
        }

        String login = parts[1];
        String password = parts[2];
        doLogin(login, password);
    }

    private static void doLogin(String login, String password) {
        try {
            UserInfo userInfo = new UserInfo();
            userInfo.login = login;
            userInfo.password = password;

            Response response = client
                    .target(BASE_URL + "/user")
                    .request(MediaType.TEXT_PLAIN)
                    .put(Entity.entity(userInfo, MediaType.APPLICATION_JSON));

            int status = response.getStatus();
            String body = safeReadEntity(response);
            response.close();

            if (status == 201) {
                System.out.println("New user registered.");
                currentLogin = login;
                currentPassword = password;
                drainAllNow();
                startBackgroundReceiver();
            } else if (status == 202) {
                System.out.println("Login successful.");
                currentLogin = login;
                currentPassword = password;
                drainAllNow();
                startBackgroundReceiver();
            } else if (status == 401) {
                System.out.println("Invalid login/password.");
            } else if (status == 400) {
                System.out.println("Bad request: " + body);
            } else {
                System.out.println("Login failed. HTTP " + status + (body.isEmpty() ? "" : " - " + body));
            }
        } catch (Exception e) {
            System.out.println("Login failed: " + e.getMessage());
        }
    }

    private static void doListUsers() {
        if (!isLoggedIn()) {
            System.out.println("You must login first.");
            return;
        }

        try {
            Invocation.Builder builder = authorizedRequest(BASE_URL + "/users", MediaType.APPLICATION_JSON);
            Response response = builder.get();

            int status = response.getStatus();
            String body = safeReadEntity(response);
            response.close();

            if (status == 200) {
                List<String> users = parseItems(body);

                if (!users.isEmpty()) {
                    System.out.println("Users:");
                    for (String user : users) {
                        System.out.println(" - " + user);
                    }
                } else {
                    System.out.println("No users found.");
                }
            } else if (status == 401) {
                System.out.println("Authentication failed.");
            } else {
                System.out.println("List failed. HTTP " + status + (body.isEmpty() ? "" : " - " + body));
            }
        } catch (Exception e) {
            System.out.println("List failed: " + e.getMessage());
        }
    }

    private static void handleMessageCommand(String input) {
        if (!isLoggedIn()) {
            System.out.println("You must login first.");
            return;
        }

        String[] parts = input.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("Usage: msg <username> <message>");
            return;
        }

        String targetUser = parts[1];
        String message = parts[2];

        doSendMessage(targetUser, message);
    }

    private static void doSendMessage(String targetUser, String message) {
        try {
            Invocation.Builder builder = authorizedRequest(
                    BASE_URL + "/" + targetUser + "/messages",
                    MediaType.TEXT_PLAIN
            );

            String jsonMessage = "\"" + escapeJson(message) + "\"";

            Response response = builder.post(Entity.entity(jsonMessage, MediaType.APPLICATION_JSON));

            int status = response.getStatus();
            String body = safeReadEntity(response);
            response.close();

            System.out.println("[Send msg] HTTP " + status + (body.isEmpty() ? "" : " - " + body));

            if (status == 201) {
                System.out.println("Message sent.");
            } else if (status == 401) {
                System.out.println("Authentication failed.");
            } else if (status == 406) {
                System.out.println("Target user has too many pending messages.");
                System.out.println("Try 'drain' in target client first.");
            } else {
                System.out.println("Message sending failed.");
            }

        } catch (Exception e) {
            System.out.println("Message sending failed: " + e.getMessage());
        }
    }

    private static void handleFileCommand(String input) {
        if (!isLoggedIn()) {
            System.out.println("You must login first.");
            return;
        }

        String[] parts = input.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("Usage: file <username> <full_path_to_file>");
            return;
        }

        String targetUser = parts[1];
        String filePath = parts[2];

        doSendFile(targetUser, filePath);
    }

    private static void doSendFile(String targetUser, String filePath) {
        try {
            File file = new File(filePath);

            if (!file.exists() || !file.isFile()) {
                System.out.println("File not found: " + filePath);
                return;
            }

            byte[] bytes = Files.readAllBytes(file.toPath());
            String encodedContent = Base64.getEncoder().encodeToString(bytes);

            FileData fileData = new FileData();
            fileData.sender = currentLogin;
            fileData.filename = file.getName();
            fileData.content = encodedContent;

            Invocation.Builder builder = authorizedRequest(
                    BASE_URL + "/" + targetUser + "/files",
                    MediaType.APPLICATION_JSON
            );

            Response response = builder.post(Entity.entity(fileData, MediaType.APPLICATION_JSON));

            int status = response.getStatus();
            String body = safeReadEntity(response);
            response.close();

            if (status == 201) {
                System.out.println("File sent.");
            } else if (status == 401) {
                System.out.println("Authentication failed.");
            } else if (status == 406) {
                System.out.println("Target user has too many pending files.");
            } else {
                System.out.println("File sending failed. HTTP " + status + (body.isEmpty() ? "" : " - " + body));
            }

        } catch (Exception e) {
            System.out.println("File sending failed: " + e.getMessage());
        }
    }

    private static void drainAllNow() {
        if (!isLoggedIn()) {
            System.out.println("You must login first.");
            return;
        }

        System.out.println("[Drain] Checking old messages/files...");
        checkIncomingMessages();
        checkIncomingFiles();
        System.out.println("[Drain] Done.");
    }

    private static void startBackgroundReceiver() {
        stopBackgroundReceiver();
        knownUsers.clear();

        backgroundThread = new Thread(() -> {
            while (running && isLoggedIn()) {
                try {
                    checkIncomingMessages();
                    checkIncomingFiles();
                    checkUserUpdates();
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    System.out.println("[Background] " + e.getMessage());
                }
            }
        });

        backgroundThread.setDaemon(true);
        backgroundThread.start();
    }

    private static void stopBackgroundReceiver() {
        if (backgroundThread != null && backgroundThread.isAlive()) {
            backgroundThread.interrupt();
        }
    }

    private static void checkIncomingMessages() {
        try {
            Invocation.Builder builder = authorizedRequest(
                    BASE_URL + "/" + currentLogin + "/messages",
                    MediaType.APPLICATION_JSON
            );

            Response response = builder.get();
            int status = response.getStatus();
            String body = safeReadEntity(response);
            response.close();

            if (status == 200) {
                List<String> ids = parseItems(body);

                if (!ids.isEmpty()) {
                    System.out.println("[Messages] Pending IDs: " + ids);
                    for (String messageId : ids) {
                        receiveAndDeleteMessage(messageId);
                    }
                }
            } else if (status == 204) {
                // no messages
            } else {
                System.out.println("[Messages] GET status: " + status + (body.isEmpty() ? "" : " - " + body));
            }
        } catch (Exception e) {
            System.out.println("[Messages] " + e.getMessage());
        }
    }

    private static void receiveAndDeleteMessage(String messageId) {
        try {
            Invocation.Builder getBuilder = authorizedRequest(
                    BASE_URL + "/" + currentLogin + "/messages/" + messageId,
                    MediaType.APPLICATION_JSON
            );

            Response getResponse = getBuilder.get();
            int getStatus = getResponse.getStatus();
            String body = safeReadEntity(getResponse);
            getResponse.close();

            if (getStatus == 200) {
                String sender = extractJsonValue(body, "sender");
                String message = extractJsonValue(body, "message");

                System.out.println();
                System.out.println("[NEW MESSAGE] from " + sender + ": " + message);
                System.out.print("> ");

                Invocation.Builder deleteBuilder = authorizedRequest(
                        BASE_URL + "/" + currentLogin + "/messages/" + messageId,
                        MediaType.APPLICATION_JSON
                );

                Response deleteResponse = deleteBuilder.delete();
                int deleteStatus = deleteResponse.getStatus();
                String deleteBody = safeReadEntity(deleteResponse);
                deleteResponse.close();

                System.out.println("[Messages] DELETE " + messageId + " -> " + deleteStatus
                        + (deleteBody.isEmpty() ? "" : " - " + deleteBody));
            } else {
                System.out.println("[Messages] GET by ID " + messageId + " -> " + getStatus
                        + (body.isEmpty() ? "" : " - " + body));
            }
        } catch (Exception e) {
            System.out.println("[Receive message] " + e.getMessage());
        }
    }

    private static void checkIncomingFiles() {
        try {
            Invocation.Builder builder = authorizedRequest(
                    BASE_URL + "/" + currentLogin + "/files",
                    MediaType.APPLICATION_JSON
            );

            Response response = builder.get();
            int status = response.getStatus();
            String body = safeReadEntity(response);
            response.close();

            if (status == 200) {
                List<String> ids = parseItems(body);

                if (!ids.isEmpty()) {
                    System.out.println("[Files] Pending IDs: " + ids);
                    for (String fileId : ids) {
                        receiveAndDeleteFile(fileId);
                    }
                }
            } else if (status == 204) {
                // no files
            } else {
                System.out.println("[Files] GET status: " + status + (body.isEmpty() ? "" : " - " + body));
            }
        } catch (Exception e) {
            System.out.println("[Files] " + e.getMessage());
        }
    }

    private static void receiveAndDeleteFile(String fileId) {
        try {
            Invocation.Builder getBuilder = authorizedRequest(
                    BASE_URL + "/" + currentLogin + "/files/" + fileId,
                    MediaType.APPLICATION_JSON
            );

            Response getResponse = getBuilder.get();
            int getStatus = getResponse.getStatus();
            String body = safeReadEntity(getResponse);
            getResponse.close();

            if (getStatus == 200) {
                FileData fileData = new FileData();
                fileData.sender = extractJsonValue(body, "sender");
                fileData.filename = extractJsonValue(body, "filename");
                fileData.content = extractJsonValue(body, "content");

                saveReceivedFile(fileData);

                System.out.println();
                System.out.println("[NEW FILE] from " + fileData.sender + ": " + fileData.filename);
                System.out.print("> ");

                Invocation.Builder deleteBuilder = authorizedRequest(
                        BASE_URL + "/" + currentLogin + "/files/" + fileId,
                        MediaType.APPLICATION_JSON
                );

                Response deleteResponse = deleteBuilder.delete();
                int deleteStatus = deleteResponse.getStatus();
                String deleteBody = safeReadEntity(deleteResponse);
                deleteResponse.close();

                System.out.println("[Files] DELETE " + fileId + " -> " + deleteStatus
                        + (deleteBody.isEmpty() ? "" : " - " + deleteBody));
            } else {
                System.out.println("[Files] GET by ID " + fileId + " -> " + getStatus
                        + (body.isEmpty() ? "" : " - " + body));
            }
        } catch (Exception e) {
            System.out.println("[Receive file] " + e.getMessage());
        }
    }

    private static void saveReceivedFile(FileData fileData) throws IOException {
        Path dir = Paths.get("received_files");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        String safeName = (fileData.filename == null || fileData.filename.trim().isEmpty())
                ? "received_file"
                : fileData.filename;

        Path output = dir.resolve(safeName);

        if (Files.exists(output)) {
            String fileName = safeName;
            String namePart = fileName;
            String extPart = "";

            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) {
                namePart = fileName.substring(0, dotIndex);
                extPart = fileName.substring(dotIndex);
            }

            int counter = 1;
            while (Files.exists(output)) {
                output = dir.resolve(namePart + "_" + counter + extPart);
                counter++;
            }
        }

        byte[] decoded = Base64.getDecoder().decode(fileData.content);
        Files.write(output, decoded);

        System.out.println("Saved file to: " + output.toAbsolutePath());
    }

    private static void checkUserUpdates() {
        try {
            Invocation.Builder builder = authorizedRequest(BASE_URL + "/users", MediaType.APPLICATION_JSON);
            Response response = builder.get();

            int status = response.getStatus();
            String body = safeReadEntity(response);
            response.close();

            if (status == 200) {
                List<String> userList = parseItems(body);
                Set<String> currentUsers = new HashSet<>(userList);

                if (!knownUsers.isEmpty()) {
                    for (String user : currentUsers) {
                        if (!knownUsers.contains(user)) {
                            System.out.println();
                            System.out.println("[USER UPDATE] " + user + " appeared in user list.");
                            System.out.print("> ");
                        }
                    }

                    for (String user : knownUsers) {
                        if (!currentUsers.contains(user)) {
                            System.out.println();
                            System.out.println("[USER UPDATE] " + user + " disappeared from user list.");
                            System.out.print("> ");
                        }
                    }
                }

                knownUsers = currentUsers;
            }
        } catch (Exception e) {
            System.out.println("[Users] " + e.getMessage());
        }
    }

    private static Invocation.Builder authorizedRequest(String url, String mediaType) {
        Invocation.Builder builder = client.target(url).request(mediaType);

        if (isLoggedIn()) {
            builder.header("Authorization", buildBasicAuthHeader(currentLogin, currentPassword));
        }

        return builder;
    }

    private static String buildBasicAuthHeader(String login, String password) {
        String raw = login + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private static boolean isLoggedIn() {
        return currentLogin != null && currentPassword != null;
    }

    private static String safeReadEntity(Response response) {
        try {
            return response.readEntity(String.class);
        } catch (Exception e) {
            return "";
        }
    }

    private static String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static List<String> parseItems(String json) {
        List<String> result = new ArrayList<>();

        if (json == null || json.isEmpty()) {
            return result;
        }

        int start = json.indexOf('[');
        int end = json.indexOf(']');

        if (start == -1 || end == -1 || end <= start) {
            return result;
        }

        String inside = json.substring(start + 1, end).trim();
        if (inside.isEmpty()) {
            return result;
        }

        String[] parts = inside.split(",");

        for (String part : parts) {
            String value = part.trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            value = value.replace("\\\"", "\"").replace("\\\\", "\\");
            if (!value.isEmpty()) {
                result.add(value);
            }
        }

        return result;
    }

    private static String extractJsonValue(String json, String key) {
        if (json == null) {
            return "";
        }

        String pattern = "\"" + key + "\"";
        int keyPos = json.indexOf(pattern);
        if (keyPos == -1) {
            return "";
        }

        int colonPos = json.indexOf(':', keyPos);
        if (colonPos == -1) {
            return "";
        }

        int firstQuote = json.indexOf('"', colonPos + 1);
        if (firstQuote == -1) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;

        for (int i = firstQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    private static void doExit() {
        running = false;
        stopBackgroundReceiver();

        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception ignored) {
        }

        try {
            if (scanner != null) {
                scanner.close();
            }
        } catch (Exception ignored) {
        }

        System.out.println("Client closed.");
    }

    @XmlRootElement
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class UserInfo {
        public String login;
        public String password;
    }

    @XmlRootElement
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class FileData {
        public String sender;
        public String filename;
        public String content;
    }
}