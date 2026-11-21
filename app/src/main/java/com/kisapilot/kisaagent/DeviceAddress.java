package com.kisapilot.kisaagent;
final class DeviceAddress {
    static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("IPv4 required");
        String[] parts = input.trim().split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("IPv4 required");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid IPv4");
            int value = Integer.parseInt(part);
            if (value > 255) throw new IllegalArgumentException("Invalid IPv4");
            if (result.length() != 0) result.append('.');
            result.append(value);
        }
        if (Integer.parseInt(parts[0]) == 0 || Integer.parseInt(parts[0]) >= 224)
            throw new IllegalArgumentException("Device IPv4 required");
        return result.toString();
    }
}
