package me.pronil.economy.database;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BiConsumer;

import me.pronil.economy.Economy;
import me.pronil.economy.account.Account;

public class Database {

    private Economy main;
    private Connection connection;
    private final String mysqlUrl;
    private final Properties mysqlInfo;
    private final String sqliteUrl;
    private volatile boolean closed;

    public Database(Economy main, File folder) throws Exception {
        folder.mkdirs();
        this.main = main;
        this.mysqlUrl = null;
        this.mysqlInfo = null;
        this.sqliteUrl = "jdbc:sqlite:" + new File(folder, "database.db");
        Class.forName("org.sqlite.JDBC").newInstance();
        connection = DriverManager.getConnection(sqliteUrl);
    }

    public Database(Economy main, boolean reconnect, String host, String database, String username, String password, int port) throws Exception {
        this.main = main;
        this.sqliteUrl = null;
        Properties info = new Properties();
        info.setProperty("useSSL", "false");

        if (reconnect) {
            info.setProperty("autoReconnect", "true");
        }
        info.setProperty("trustServerCertificate", "true");
        info.setProperty("user", username);
        info.setProperty("password", password);
        this.mysqlInfo = info;
        this.mysqlUrl = "jdbc:mysql://" + host + ":" + port + "/" + database;

        Class.forName("com.mysql.jdbc.Driver").newInstance();
        connection = DriverManager.getConnection(mysqlUrl, mysqlInfo);
    }

    private synchronized Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("Database is already closed.");
        }
        if (connection != null && !connection.isClosed()) {
            return connection;
        }

        if (sqliteUrl != null) {
            connection = DriverManager.getConnection(sqliteUrl);
        } else if (mysqlUrl != null && mysqlInfo != null) {
            connection = DriverManager.getConnection(mysqlUrl, mysqlInfo);
        } else {
            throw new SQLException("Database connection is not configured.");
        }
        return connection;
    }

    public synchronized void createTable(String sqlURL) throws SQLException {
        try (PreparedStatement statement = getConnection().prepareStatement(sqlURL)) {
            statement.executeUpdate();
        }
    }

    public synchronized boolean createAccount(Account account) {
        try (PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + main.getTable() + " VALUES (?, ?, ?)")) {
            insert.setString(1, account.getUUID().toString());
            insert.setString(2, account.getName());
            insert.setDouble(3, account.getBalance());
            insert.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public synchronized boolean hasAccount(Account account) {
        try (PreparedStatement select = getConnection().prepareStatement("SELECT UUID, BALANCE FROM " + main.getTable() + " WHERE UUID = ?")) {
            select.setString(1, account.getUUID().toString());
            try (ResultSet result = select.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public synchronized void updateAccount(Account account) {
        try (PreparedStatement statement = getConnection().prepareStatement("UPDATE " + main.getTable() + " SET UUID = ?, NAME = ?, BALANCE = ? WHERE UUID = ?;")) {
            statement.setString(1, account.getUUID().toString());
            statement.setString(2, account.getName());
            statement.setDouble(3, account.getBalance());
            statement.setString(4, account.getUUID().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized void getAccount(Account account, Runnable ifFailed) {
        try (PreparedStatement select = getConnection().prepareStatement("SELECT BALANCE FROM " + main.getTable() + " WHERE UUID = ?")) {
            select.setString(1, account.getUUID().toString());
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    account.setBalance(result.getDouble(1));
                } else if (ifFailed != null) {
                    ifFailed.run();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void getOrInsertAccountAsync(Account account) {
        main.getExecutor().execute(() -> getAccount(account, () -> createAccount(account)));
    }

    public void updateAccountAsync(Account account) {

        main.getExecutor().execute(() -> updateAccount(account));
    }

    public void getTopAsync(int page, BiConsumer<Double, List<Account>> consumer) {
        main.getExecutor().execute(() -> {
            synchronized (Database.this) {
                try (PreparedStatement select = getConnection().prepareStatement("SELECT NAME, BALANCE FROM " + main.getTable() + " ORDER BY BALANCE DESC")) {
                    try (ResultSet result = select.executeQuery()) {
                        double all_balance = 0;
                        List<Account> list = new ArrayList<>();
                        for (int i = 0; result.next(); i++) {
                            double balance = result.getDouble(2);
                            if (page <= i && page + 10 > i) {
                                String name = result.getString(1);
                                if (name != null && name.length() > 0) {
                                    Account account = new Account(null, name);
                                    account.setBalance(balance);
                                    list.add(account);
                                }
                            }
                            all_balance += balance;
                        }
                        consumer.accept(all_balance, list);
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public synchronized List<Account> getAllAccounts() {
        List<Account> accounts = new ArrayList<>();
        try (PreparedStatement select = getConnection().prepareStatement("SELECT UUID, NAME, BALANCE FROM " + main.getTable())) {
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    String uuidString = result.getString(1);
                    String name = result.getString(2);
                    double balance = result.getDouble(3);
                    Account account = new Account(uuidString != null ? java.util.UUID.fromString(uuidString) : null, name);
                    account.setBalance(balance);
                    accounts.add(account);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return accounts;
    }

    public synchronized void close() {
        closed = true;
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            connection = null;
        }
    }

}
