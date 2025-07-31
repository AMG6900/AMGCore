package amg.plugins.aMGCore.models;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BanData {
    private final String playerName;
    private final String staffMember;
    private final String reason;
    private final long banTime;
    private final long duration; // -1 for permanent
    private final Set<String> ipAddresses;
    private final List<BanHistoryEntry> banHistory;

    public BanData(@NotNull String playerName, @NotNull String staffMember, @NotNull String reason, long duration) {
        this.playerName = Objects.requireNonNull(playerName, "Player name cannot be null");
        this.staffMember = Objects.requireNonNull(staffMember, "Staff member cannot be null");
        this.reason = Objects.requireNonNull(reason, "Reason cannot be null");
        this.duration = duration;
        this.banTime = System.currentTimeMillis();
        this.ipAddresses = Collections.synchronizedSet(new HashSet<>());
        this.banHistory = new CopyOnWriteArrayList<>();
        
        // Add initial ban to history
        addHistoryEntry("BANNED", staffMember, reason);
    }

    @NotNull
    public String getPlayerName() {
        return playerName;
    }

    @NotNull
    public String getStaffMember() {
        return staffMember;
    }

    @NotNull
    public String getReason() {
        return reason;
    }

    public long getBanTime() {
        return banTime;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isPermanent() {
        return duration < 0;
    }

    public boolean hasExpired() {
        return !isPermanent() && System.currentTimeMillis() >= (banTime + duration);
    }

    public long getTimeRemaining() {
        if (isPermanent()) {
            return -1;
        }
        return Math.max(0, (banTime + duration) - System.currentTimeMillis());
    }

    @NotNull
    public Set<String> getIpAddresses() {
        return Collections.unmodifiableSet(ipAddresses);
    }

    public void addIpAddress(@Nullable String ip) {
        if (ip != null && !ip.isEmpty()) {
            ipAddresses.add(ip.toLowerCase());
        }
    }

    public boolean hasIpAddress(@Nullable String ip) {
        return ip != null && !ip.isEmpty() && ipAddresses.contains(ip.toLowerCase());
    }

    @NotNull
    public List<BanHistoryEntry> getBanHistory() {
        return Collections.unmodifiableList(banHistory);
    }

    private void addHistoryEntry(@NotNull String action, @NotNull String staff, @NotNull String note) {
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(staff, "Staff cannot be null");
        Objects.requireNonNull(note, "Note cannot be null");
        
        banHistory.add(new BanHistoryEntry(action, staff, note));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BanData banData)) return false;
        return playerName.equalsIgnoreCase(banData.playerName);
    }

    @Override
    public int hashCode() {
        return playerName.toLowerCase().hashCode();
    }

    @Override
    public String toString() {
        return "BanData{" +
                "playerName='" + playerName + '\'' +
                ", staffMember='" + staffMember + '\'' +
                ", reason='" + reason + '\'' +
                ", banTime=" + banTime +
                ", duration=" + duration +
                ", ipAddresses=" + ipAddresses +
                ", historySize=" + banHistory.size() +
                '}';
    }

    public static final class BanHistoryEntry {
        private final String action;
        private final String staff;
        private final String note;
        private final long timestamp;

        private BanHistoryEntry(@NotNull String action, @NotNull String staff, @NotNull String note) {
            this.action = Objects.requireNonNull(action, "Action cannot be null");
            this.staff = Objects.requireNonNull(staff, "Staff cannot be null");
            this.note = Objects.requireNonNull(note, "Note cannot be null");
            this.timestamp = System.currentTimeMillis();
        }

        @NotNull
        public String getAction() {
            return action;
        }

        @NotNull
        public String getStaff() {
            return staff;
        }

        @NotNull
        public String getNote() {
            return note;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BanHistoryEntry that)) return false;
            return timestamp == that.timestamp &&
                   action.equals(that.action) &&
                   staff.equals(that.staff) &&
                   note.equals(that.note);
        }

        @Override
        public int hashCode() {
            return Objects.hash(action, staff, note, timestamp);
        }

        @Override
        public String toString() {
            return "BanHistoryEntry{" +
                    "action='" + action + '\'' +
                    ", staff='" + staff + '\'' +
                    ", note='" + note + '\'' +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
} 