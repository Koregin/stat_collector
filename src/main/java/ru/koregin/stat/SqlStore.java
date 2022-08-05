package ru.koregin.stat;

import java.io.InputStream;
import java.sql.*;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class SqlStore implements AutoCloseable {

    private Connection cn;

    public SqlStore() {
    }

    public static void main(String[] args) {
        Map<String, String> sws = new HashMap<>();
        try (SqlStore store = new SqlStore()) {
            store.init();
            sws = store.findAllSwitches();
        } catch (Exception e) {
            e.printStackTrace();
        }
        for (String name : sws.keySet()) {
            System.out.println(name + " : " + sws.get(name));
        }
    }

    public void init() {
        try (InputStream in = SqlStore.class.getClassLoader()
                .getResourceAsStream("app.properties")) {
            Properties config = new Properties();
            config.load(in);
            Class.forName(config.getProperty("driver-class-name"));
            cn = DriverManager.getConnection(
                    config.getProperty("url"),
                    config.getProperty("username"),
                    config.getProperty("password")
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, String> findAllSwitches() {
        Map<String, String> switches = new LinkedHashMap<>();
        try (PreparedStatement ps =
                     cn.prepareStatement("select name, INET_NTOA(ip) as ip from switches where comment LIKE '%C2950%'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    switches.put(rs.getString("name"), rs.getString("ip"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return switches;
    }

    @Override
    public void close() throws Exception {
        if (cn != null) {
            cn.close();
        }
    }
}
