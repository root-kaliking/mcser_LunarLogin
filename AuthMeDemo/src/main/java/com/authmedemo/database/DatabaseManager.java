package com.authmedemo.database;

// ===================== 实现思路 =====================
// SQLite数据库管理器
// 【硬性规则】：所有数据库IO操作必须通过异步调度执行，严禁主线程直接调用同步方法
// 表结构（authme_players）：
//   id           INTEGER PRIMARY KEY AUTOINCREMENT  自增主键
//   real_name    TEXT                               玩家原始用户名（保留大小写）
//   lower_name   TEXT UNIQUE                        玩家小写用户名（唯一索引，防重名）
//   offline_uuid TEXT UNIQUE                        离线UUID（唯一索引，按UUID查）
//   password     TEXT                               BCrypt密码哈希（绝对不是明文！）
//   last_ip      TEXT                               最后登录IP
//   reg_date     BIGINT                             注册时间戳（毫秒）
//   last_login   BIGINT                             最后登录时间戳（毫秒）
//
// 对外暴露的异步接口（返回void，通过回调在主线程处理结果）：
//   - getAuthByUuid(uuid, callback)        按UUID查账号（玩家进服时调用）
//   - getAuthByName(name, callback)        按用户名查账号（管理员改密码用）
//   - insertAuth(auth, callback)           注册新账号
//   - updatePassword(uuid, newHash, cb)    修改密码
//   - updateLoginInfo(uuid, ip, time, cb)  更新登录IP和时间
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.model.PlayerAuth;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager {

    // 插件实例引用，用于异步调度
    private final Plugin plugin;
    private final Logger logger;

    // SQLite数据库文件路径
    private final File dbFile;

    // SQLite JDBC连接URL（SQLite是文件型数据库，URL指向文件）
    private final String jdbcUrl;

    // 缓存的数据库连接（SQLite单线程串行访问，共用一个连接即可）
    private Connection connection;

    /**
     * 构造函数
     *
     * @param plugin   插件主类实例
     * @param dbFolder 插件数据文件夹（getDataFolder()）
     * @param filename 数据库文件名（如 "authme.db"）
     */
    public DatabaseManager(Plugin plugin, File dbFolder, String filename) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        // 确保插件数据目录存在
        if (!dbFolder.exists()) {
            dbFolder.mkdirs();
        }
        this.dbFile = new File(dbFolder, filename);
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
    }

    // ===================== 连接管理 =====================

    /**
     * 同步初始化：加载JDBC驱动、建立连接、建表
     * 插件 onEnable 时调用（可以在主线程，因为只执行一次）
     *
     * @return true=初始化成功 false=失败
     */
    public synchronized boolean init() {
        try {
            // 加载SQLite JDBC驱动
            Class.forName("org.sqlite.JDBC");
            // 建立连接
            connection = DriverManager.getConnection(jdbcUrl);
            logger.info("已连接到SQLite数据库: " + dbFile.getName());
            // 建表（IF NOT EXISTS幂等）
            createTable();
            return true;
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "找不到SQLite JDBC驱动！请检查依赖是否正确shade进jar", e);
            return false;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "连接SQLite数据库失败！", e);
            return false;
        }
    }

    /**
     * 关闭数据库连接（插件onDisable时调用）
     */
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("SQLite数据库连接已关闭");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "关闭数据库连接时出错", e);
        }
    }

    /**
     * 创建authme_players表（幂等）
     */
    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS authme_players (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "real_name TEXT NOT NULL," +
                "lower_name TEXT NOT NULL UNIQUE," +
                "offline_uuid TEXT NOT NULL UNIQUE," +
                "password TEXT NOT NULL," +        // 这里存的是BCrypt哈希，不是明文！
                "last_ip TEXT," +
                "reg_date BIGINT NOT NULL," +
                "last_login BIGINT)";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            logger.info("authme_players表已就绪");
        }
    }

    // ===================== 同步DAO内部方法（仅在异步线程中调用）=====================

    /**
     * 按离线UUID查询账号（同步方法，必须异步调用！）
     *
     * @return PlayerAuth或null（没注册）
     */
    private PlayerAuth syncGetAuthByUuid(String offlineUuid) {
        String sql = "SELECT * FROM authme_players WHERE offline_uuid = ? LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, offlineUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return extractAuthFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询玩家账号失败 (uuid=" + offlineUuid + ")", e);
        }
        return null;
    }

    /**
     * 按小写用户名查询账号（同步方法）
     * 管理员改密码时用（因为管理员只知道玩家名，不一定有UUID）
     */
    private PlayerAuth syncGetAuthByName(String lowerName) {
        String sql = "SELECT * FROM authme_players WHERE lower_name = ? LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lowerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return extractAuthFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询玩家账号失败 (name=" + lowerName + ")", e);
        }
        return null;
    }

    /**
     * 从ResultSet解析出PlayerAuth对象
     */
    private PlayerAuth extractAuthFromResultSet(ResultSet rs) throws SQLException {
        PlayerAuth auth = new PlayerAuth();
        auth.setRealName(rs.getString("real_name"));
        auth.setLowerName(rs.getString("lower_name"));
        auth.setOfflineUuid(rs.getString("offline_uuid"));
        auth.setPasswordHash(rs.getString("password"));
        auth.setLastIp(rs.getString("last_ip"));
        auth.setRegisterDate(rs.getLong("reg_date"));
        long lastLogin = rs.getLong("last_login");
        auth.setLastLoginDate(lastLogin == 0 ? auth.getRegisterDate() : lastLogin);
        return auth;
    }

    /**
     * 插入新玩家账号（注册用，同步方法）
     *
     * @return true=插入成功 false=失败或冲突
     */
    private boolean syncInsertAuth(PlayerAuth auth) {
        String sql = "INSERT INTO authme_players (real_name, lower_name, offline_uuid, password, last_ip, reg_date, last_login) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, auth.getRealName());
            ps.setString(2, auth.getLowerName());
            ps.setString(3, auth.getOfflineUuid());
            ps.setString(4, auth.getPasswordHash()); // BCrypt哈希
            ps.setString(5, auth.getLastIp());
            ps.setLong(6, auth.getRegisterDate());
            ps.setLong(7, auth.getLastLoginDate());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "注册新玩家失败 (name=" + auth.getLowerName() + ")", e);
            return false;
        }
    }

    /**
     * 更新玩家密码哈希（修改密码/管理员改密码用，同步方法）
     *
     * @return true=更新成功
     */
    private boolean syncUpdatePassword(String offlineUuid, String newPasswordHash) {
        String sql = "UPDATE authme_players SET password = ? WHERE offline_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, newPasswordHash);
            ps.setString(2, offlineUuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "修改玩家密码失败 (uuid=" + offlineUuid + ")", e);
            return false;
        }
    }

    /**
     * 更新玩家登录信息（IP和最后登录时间，登录成功时调用，同步方法）
     */
    private boolean syncUpdateLoginInfo(String offlineUuid, String ip, long loginTime) {
        String sql = "UPDATE authme_players SET last_ip = ?, last_login = ? WHERE offline_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, loginTime);
            ps.setString(3, offlineUuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "更新玩家登录信息失败 (uuid=" + offlineUuid + ")", e);
            return false;
        }
    }

    // ===================== 异步对外接口（通过Bukkit调度器异步执行）=====================

    /**
     * 通用异步执行：把一个数据库任务丢到异步线程池执行
     *
     * @param asyncTask  异步任务（返回T类型结果，在异步线程执行）
     * @param mainThreadCallback 异步任务完成后，在主线程回调结果（可以是null）
     * @param <T>        结果类型
     */
    private <T> void runAsync(java.util.function.Supplier<T> asyncTask, Consumer<T> mainThreadCallback) {
        // Bukkit.getScheduler().runTaskAsynchronously() 开启异步线程，避免阻塞主线程
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T result;
            try {
                result = asyncTask.get();
            } catch (Exception e) {
                logger.log(Level.WARNING, "数据库异步任务执行异常", e);
                result = null;
            }
            // 如果有回调，把结果丢回主线程执行（Bukkit API只能主线程调用）
            if (mainThreadCallback != null) {
                final T finalResult = result;
                Bukkit.getScheduler().runTask(plugin, () -> mainThreadCallback.accept(finalResult));
            }
        });
    }

    /**
     * 异步：按UUID查询玩家账号（玩家进服时调用）
     *
     * @param offlineUuid 离线UUID字符串
     * @param callback    在主线程回调结果（PlayerAuth或null=未注册）
     */
    public void getAuthByUuid(String offlineUuid, Consumer<PlayerAuth> callback) {
        runAsync(() -> syncGetAuthByUuid(offlineUuid), callback);
    }

    /**
     * 异步：按用户名查询玩家账号（管理员命令用）
     */
    public void getAuthByName(String playerName, Consumer<PlayerAuth> callback) {
        final String lowerName = playerName.toLowerCase();
        runAsync(() -> syncGetAuthByName(lowerName), callback);
    }

    /**
     * 异步：插入新注册的账号
     *
     * @param auth     新账号数据
     * @param callback 在主线程回调布尔值（true=注册成功）
     */
    public void insertAuth(PlayerAuth auth, Consumer<Boolean> callback) {
        runAsync(() -> syncInsertAuth(auth), callback);
    }

    /**
     * 异步：修改密码
     */
    public void updatePassword(String offlineUuid, String newPasswordHash, Consumer<Boolean> callback) {
        runAsync(() -> syncUpdatePassword(offlineUuid, newPasswordHash), callback);
    }

    /**
     * 异步：更新登录信息（IP + 登录时间）
     * 登录成功后调用，可以不传callback（无需回主线程）
     */
    public void updateLoginInfo(String offlineUuid, String ip, long loginTime) {
        runAsync(() -> syncUpdateLoginInfo(offlineUuid, ip, loginTime), null);
    }
}
