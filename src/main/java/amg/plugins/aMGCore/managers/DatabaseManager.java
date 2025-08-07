package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.models.PlayerData;
import amg.plugins.aMGCore.utils.DebugLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DatabaseManager {
    private final String dbUrl;
    private final Properties dbProperties;
    private static final String H2_DRIVER_CLASS = "org.h2.Driver";
    private static final String RELOCATED_H2_DRIVER_CLASS = "amg.plugins.aMGCore.lib.h2.Driver";
    
    // Connection pooling
    private HikariDataSource connectionPool;
    
    // Prepared statement cache
    private final Map<String, String> preparedStatements = new ConcurrentHashMap<>();
    private final Map<String, PreparedStatement> cachedStatements = new ConcurrentHashMap<>();
    
    // Performance metrics
    private final AtomicLong queryCount = new AtomicLong(0);
    private final AtomicLong totalQueryTime = new AtomicLong(0);
    private final AtomicLong slowQueryCount = new AtomicLong(0);
    private final AtomicInteger connectionErrorCount = new AtomicInteger(0);
    private static final long SLOW_QUERY_THRESHOLD = 100; // ms

    public DatabaseManager(AMGCore plugin) {
        // Load the H2 database driver
        try {
            // Try the relocated driver class first (for production)
            try {
                Class.forName(RELOCATED_H2_DRIVER_CLASS);
            } catch (ClassNotFoundException e) {
                // Fall back to the original driver class (for development)
                Class.forName(H2_DRIVER_CLASS);
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("Failed to load H2 database driver: " + e.getMessage());
            plugin.getLogger().severe("Make sure the H2 database library is properly included in the plugin JAR");
            e.printStackTrace();
            throw new RuntimeException("Failed to load H2 database driver", e);
        }
        
        try {
            // Get database configuration
            FileConfiguration config = plugin.getConfig();
            String dbName = config.getString("database.name", "amgcore");
            String dbPassword = config.getString("database.password", "changeme");
            boolean useEncryption = config.getBoolean("database.encrypt", true);
            
            // Set up database file
            File dbFile = new File(plugin.getDataFolder(), "data" + File.separator + dbName);
            // Make sure the parent directory exists
            File dataDir = new File(plugin.getDataFolder(), "data");
            if (!dataDir.exists() && !dataDir.mkdirs()) {
                plugin.getLogger().warning("Failed to create data directory for database");
            }
            
            if (useEncryption) {
                this.dbUrl = "jdbc:h2:" + dbFile.getAbsolutePath() + ";CIPHER=AES";
                
                // Set up database properties with encryption
                this.dbProperties = new Properties();
                this.dbProperties.setProperty("user", "sa");
                this.dbProperties.setProperty("password", dbPassword + " " + dbPassword);
            } else {
                this.dbUrl = "jdbc:h2:" + dbFile.getAbsolutePath();
                
                // Set up database properties without encryption
                this.dbProperties = new Properties();
                this.dbProperties.setProperty("user", "sa");
                this.dbProperties.setProperty("password", dbPassword);
            }
            
            // Initialize connection pool
            initializeConnectionPool(plugin);
            
            // Test connection and initialize database
            try (Connection testConn = getConnection()) {
                initializeDatabase();
                initializePreparedStatements();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database connection error: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize database connection", e);
        } catch (Exception e) {
            plugin.getLogger().severe("Unexpected error during database initialization: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize database", e);
        }
    }
    
    /**
     * Initialize the connection pool using HikariCP
     */
    private void initializeConnectionPool(AMGCore plugin) {
        FileConfiguration config = plugin.getConfig();
        
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(dbUrl);
        hikariConfig.setDataSourceProperties(dbProperties);
        
        // Configure pool settings from config or use defaults
        hikariConfig.setMaximumPoolSize(config.getInt("storage.database.max_pool_size", 10));
        hikariConfig.setMinimumIdle(config.getInt("storage.database.min_idle", 5));
        hikariConfig.setIdleTimeout(config.getLong("storage.database.idle_timeout", 60000));
        hikariConfig.setConnectionTimeout(config.getLong("storage.database.connection_timeout", 30000));
        hikariConfig.setMaxLifetime(config.getLong("storage.database.max_lifetime", 1800000));
        
        // Set pool name for easier debugging
        hikariConfig.setPoolName("AMGCore-DB-Pool");
        
        // Enable metrics tracking
        hikariConfig.setMetricRegistry(null); // Can be replaced with actual metrics registry if needed
        
        // Create the connection pool
        connectionPool = new HikariDataSource(hikariConfig);
    }

    private void initializeDatabase() {
        try {
            Connection conn = getConnection();
            if (conn == null) {
                throw new SQLException("Failed to get database connection");
            }
            
            try {
                // Create tables
                try (Statement stmt = conn.createStatement()) {
                    // Player data table
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS player_data (
                            uuid VARCHAR(36) PRIMARY KEY,
                            name VARCHAR(16) NOT NULL,
                            money DOUBLE NOT NULL DEFAULT 0,
                            job VARCHAR(32) NOT NULL DEFAULT 'unemployed',
                            last_location_world VARCHAR(64),
                            last_location_x DOUBLE,
                            last_location_y DOUBLE,
                            last_location_z DOUBLE,
                            last_location_yaw FLOAT,
                            last_location_pitch FLOAT,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);
                    
                    // Known IPs table
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS known_ips (
                            uuid VARCHAR(36) NOT NULL,
                            ip VARCHAR(45) NOT NULL,
                            first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (uuid, ip),
                            FOREIGN KEY (uuid) REFERENCES player_data(uuid) ON DELETE CASCADE
                        )
                    """);
                    
                    // Create jails table
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS jails (
                            name VARCHAR(32) PRIMARY KEY,
                            world VARCHAR(64) NOT NULL,
                            x DOUBLE NOT NULL,
                            y DOUBLE NOT NULL,
                            z DOUBLE NOT NULL,
                            yaw FLOAT NOT NULL,
                            pitch FLOAT NOT NULL,
                            created_by VARCHAR(36) NOT NULL,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);
                    
                    // Create jailed players table
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS jailed_players (
                            uuid VARCHAR(36) PRIMARY KEY,
                            jail_name VARCHAR(32) NOT NULL,
                            jailed_by VARCHAR(36) NOT NULL,
                            reason VARCHAR(255),
                            jail_time BIGINT NOT NULL,
                            remaining_time BIGINT NOT NULL,
                            jailed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            FOREIGN KEY (jail_name) REFERENCES jails(name) ON DELETE CASCADE,
                            FOREIGN KEY (uuid) REFERENCES player_data(uuid) ON DELETE CASCADE
                        )
                    """);
                    
                    // Create indexes for better performance
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_data_name ON player_data(name)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_known_ips_ip ON known_ips(ip)");
                    
                    DebugLogger.debug("Database", "Database tables initialized");
                }
            } finally {
                // Return connection to the pool
                conn.close();
            }
        } catch (SQLException e) {
            DebugLogger.severe("Database", "Failed to initialize database", e);
            throw new RuntimeException("Failed to initialize database: " + e.getMessage(), e);
        }
    }
    
    /**
     * Initializes the prepared statement cache
     */
    private void initializePreparedStatements() {
        // Player data statements
        preparedStatements.put("loadPlayerData", "SELECT * FROM player_data WHERE uuid = ?");
        preparedStatements.put("updatePlayerName", "UPDATE player_data SET name = ? WHERE uuid = ?");
        preparedStatements.put("insertPlayerData", "INSERT INTO player_data (uuid, name, money, job) VALUES (?, ?, ?, ?)");
        preparedStatements.put("updatePlayerData", """
            UPDATE player_data SET
                name = ?,
                money = ?,
                job = ?,
                last_location_world = ?,
                last_location_x = ?,
                last_location_y = ?,
                last_location_z = ?,
                last_location_yaw = ?,
                last_location_pitch = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ?
        """);
        
        // Known IPs statements
        preparedStatements.put("loadKnownIps", "SELECT ip FROM known_ips WHERE uuid = ?");
        preparedStatements.put("updateKnownIp", "MERGE INTO known_ips (uuid, ip, last_seen) VALUES (?, ?, CURRENT_TIMESTAMP)");
        
        DebugLogger.debug("Database", "Prepared statement cache initialized with " + preparedStatements.size() + " statements");
    }

    /**
     * Gets a connection from the connection pool.
     * 
     * @return A connection to the database
     * @throws SQLException if a database access error occurs
     */
    public Connection getConnection() throws SQLException {
        try {
            return connectionPool.getConnection();
        } catch (SQLException e) {
            connectionErrorCount.incrementAndGet();
            DebugLogger.severe("Database", "Failed to get connection from pool: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Gets a prepared statement from the cache or creates a new one
     * 
     * @param conn The database connection
     * @param key The statement key in the cache
     * @return The prepared statement
     * @throws SQLException if a database access error occurs
     */
    private PreparedStatement getPreparedStatement(Connection conn, String key) throws SQLException {
        String sql = preparedStatements.get(key);
        if (sql == null) {
            throw new IllegalArgumentException("No prepared statement found for key: " + key);
        }
        
        return conn.prepareStatement(sql);
    }
    
    /**
     * Executes a database operation with performance tracking
     * 
     * @param <T> The return type
     * @param operation The database operation to execute
     * @return The result of the operation
     * @throws SQLException if a database access error occurs
     */
    private <T> T executeWithMetrics(DatabaseOperation<T> operation) throws SQLException {
        long startTime = System.currentTimeMillis();
        queryCount.incrementAndGet();
        
        try {
            return operation.execute();
        } catch (SQLException e) {
            connectionErrorCount.incrementAndGet();
            throw e;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            totalQueryTime.addAndGet(executionTime);
            
            if (executionTime > SLOW_QUERY_THRESHOLD) {
                slowQueryCount.incrementAndGet();
                DebugLogger.debug("Database", "Slow query detected: " + executionTime + "ms");
            }
        }
    }
    
    /**
     * Functional interface for database operations
     */
    @FunctionalInterface
    private interface DatabaseOperation<T> {
        T execute() throws SQLException;
    }

    @Nullable
    public PlayerData loadPlayerData(@NotNull UUID uuid, @NotNull String name) {
        try {
            return executeWithMetrics(() -> {
                try (Connection conn = getConnection()) {
                    // Try to load existing player data
                    try (PreparedStatement stmt = getPreparedStatement(conn, "loadPlayerData")) {
                        stmt.setString(1, uuid.toString());
                        ResultSet rs = stmt.executeQuery();
                        
                        if (rs.next()) {
                            PlayerData data = createPlayerDataFromResultSet(rs);
                            
                            // Update the name if it changed
                            if (!name.equals(data.getName())) {
                                try (PreparedStatement updateStmt = getPreparedStatement(conn, "updatePlayerName")) {
                                    updateStmt.setString(1, name);
                                    updateStmt.setString(2, uuid.toString());
                                    updateStmt.executeUpdate();
                                }
                            }
                            
                            return data;
                        }
                    }
                    
                    // Create new player data if not found
                    PlayerData data = new PlayerData(uuid.toString(), name);
                    
                    try (PreparedStatement stmt = getPreparedStatement(conn, "insertPlayerData")) {
                        stmt.setString(1, uuid.toString());
                        stmt.setString(2, name);
                        stmt.setDouble(3, data.getMoney());
                        stmt.setString(4, data.getJob());
                        stmt.executeUpdate();
                    }
                    
                    return data;
                }
            });
        } catch (SQLException e) {
            DebugLogger.severe("Database", "Failed to load player data for " + name, e);
            return null;
        }
    }

    public void savePlayerData(@NotNull PlayerData data) {
        try {
            executeWithMetrics(() -> {
                try (Connection conn = getConnection()) {
                    try (PreparedStatement stmt = getPreparedStatement(conn, "updatePlayerData")) {
                        stmt.setString(1, data.getName());
                        stmt.setDouble(2, data.getMoney());
                        stmt.setString(3, data.getJob());
                        
                        if (data.getLastLocation() != null) {
                            stmt.setString(4, data.getLastLocation().getWorld().getName());
                            stmt.setDouble(5, data.getLastLocation().getX());
                            stmt.setDouble(6, data.getLastLocation().getY());
                            stmt.setDouble(7, data.getLastLocation().getZ());
                            stmt.setFloat(8, data.getLastLocation().getYaw());
                            stmt.setFloat(9, data.getLastLocation().getPitch());
                        } else {
                            stmt.setNull(4, Types.VARCHAR);
                            stmt.setNull(5, Types.DOUBLE);
                            stmt.setNull(6, Types.DOUBLE);
                            stmt.setNull(7, Types.DOUBLE);
                            stmt.setNull(8, Types.FLOAT);
                            stmt.setNull(9, Types.FLOAT);
                        }
                        
                        stmt.setString(10, data.getUuid());
                        stmt.executeUpdate();
                    }
                    
                    // Update known IPs
                    try (PreparedStatement stmt = getPreparedStatement(conn, "updateKnownIp")) {
                        for (String ip : data.getKnownIps()) {
                            stmt.setString(1, data.getUuid());
                            stmt.setString(2, ip);
                            stmt.executeUpdate();
                        }
                    }
                    
                    return null;
                }
            });
        } catch (SQLException e) {
            DebugLogger.severe("Database", "Failed to save player data for " + data.getName(), e);
        }
    }

    private PlayerData createPlayerDataFromResultSet(ResultSet rs) throws SQLException {
        PlayerData data = new PlayerData(
            rs.getString("uuid"),
            rs.getString("name")
        );
        
        data.setMoney(rs.getDouble("money"));
        data.setJob(rs.getString("job"));
        
        // Load known IPs
        try (PreparedStatement stmt = getPreparedStatement(rs.getStatement().getConnection(), "loadKnownIps")) {
            stmt.setString(1, data.getUuid());
            ResultSet ipRs = stmt.executeQuery();
            while (ipRs.next()) {
                data.updateIp(ipRs.getString("ip"));
            }
        }
        
        return data;
    }
    
    /**
     * Gets database performance metrics
     * 
     * @return A string containing performance metrics
     */
    public String getPerformanceMetrics() {
        long queries = queryCount.get();
        long totalTime = totalQueryTime.get();
        long slowQueries = slowQueryCount.get();
        int errors = connectionErrorCount.get();
        double avgTime = queries > 0 ? (double) totalTime / queries : 0;
        double slowPercentage = queries > 0 ? (double) slowQueries / queries * 100 : 0;
        
        // Add connection pool metrics
        int activeConnections = connectionPool.getHikariPoolMXBean().getActiveConnections();
        int idleConnections = connectionPool.getHikariPoolMXBean().getIdleConnections();
        int totalConnections = connectionPool.getHikariPoolMXBean().getTotalConnections();
        
        return String.format(
            "Database Metrics: %d queries, %.2fms avg, %d slow queries (%.2f%%), %d errors | Pool: %d active, %d idle, %d total",
            queries, avgTime, slowQueries, slowPercentage, errors, 
            activeConnections, idleConnections, totalConnections
        );
    }
    
    /**
     * Resets the performance metrics
     */
    public void resetPerformanceMetrics() {
        queryCount.set(0);
        totalQueryTime.set(0);
        slowQueryCount.set(0);
        connectionErrorCount.set(0);
    }

    public void close() {
        try {
            // Log performance metrics before closing
            DebugLogger.debug("Database", getPerformanceMetrics());
            
            // Close cached statements
            for (PreparedStatement stmt : cachedStatements.values()) {
                try {
                    stmt.close();
                } catch (SQLException ignored) {
                    // Ignore errors when closing statements
                }
            }
            cachedStatements.clear();
            
            // Close connection pool
            if (connectionPool != null && !connectionPool.isClosed()) {
                // Execute shutdown command before closing pool
                try (Connection conn = connectionPool.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("SHUTDOWN");
                } catch (SQLException e) {
                    DebugLogger.warning("Database", "Error executing shutdown command: " + e.getMessage());
                }
                
                // Close the pool
                connectionPool.close();
            }
        } catch (Exception e) {
            DebugLogger.severe("Database", "Error while closing database", e);
        }
    }
} 