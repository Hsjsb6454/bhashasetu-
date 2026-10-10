import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/* ======================= 1. INTERFACES + EXCEPTIONS ======================= */
interface Translatable {
    String translate(String word, String from, String to) throws TranslationException;
}

/** Generic DAO interface: T = row type, K = key type */
interface Dao<T, K> {
    List<T> findAll() throws SQLException;
    default void delete(K key) throws SQLException {          // default method
        throw new UnsupportedOperationException("Delete not supported");
    }
}

class TranslationException extends Exception {                // custom checked exception
    TranslationException(String msg) { super(msg); }
}

/* ======================= 2. INHERITANCE + POLYMORPHISM ==================== */
abstract class Person {
    final int id;
    final String name;
    Person(int id, String name) { this.id = id; this.name = name; }
    abstract String role();
    boolean canManageUsers() { return false; }

    static Person of(int id, String name, String role) {
        return role.equals("admin") ? new AdminUser(id, name) : new Learner(id, name);
    }
}
class Learner extends Person {
    Learner(int id, String name) { super(id, name); }
    @Override String role() { return "user"; }
}
class AdminUser extends Person {
    AdminUser(int id, String name) { super(id, name); }
    @Override String role() { return "admin"; }
    @Override boolean canManageUsers() { return true; }       // overridden behaviour
}

/* ======================= 3. COLLECTIONS + GENERICS ======================== */
class DictionaryTranslator implements Translatable {
    static final List<String> LANGS = List.of("Hindi", "Bengali", "Tamil", "Telugu", "Marathi",
            "Gujarati", "Punjabi", "Kannada", "Malayalam");
    // english word -> (language -> translated word)
    private final Map<String, Map<String, String>> dict = new LinkedHashMap<>();

    DictionaryTranslator() {
        add("hello", "नमस्ते", "নমস্কার", "வணக்கம்", "నమస్కారం", "नमस्कार", "નમસ્તે", "ਸਤ ਸ੍ਰੀ ਅਕਾਲ", "ನಮಸ್ಕಾರ", "നമസ്കാരം");
        add("thank you", "धन्यवाद", "ধন্যবাদ", "நன்றி", "ధన్యవాదాలు", "धन्यवाद", "આભાર", "ਧੰਨਵਾਦ", "ಧನ್ಯವಾದಗಳು", "നന്ദി");
        add("water", "पानी", "জল", "தண்ணீர்", "నీరు", "पाणी", "પાણી", "ਪਾਣੀ", "ನೀರು", "വെള്ളം");
        // add more words here, same order as LANGS
    }

    private void add(String english, String... t) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < LANGS.size(); i++) m.put(LANGS.get(i), t[i]);
        dict.put(english, m);
    }

    @Override
    public String translate(String word, String from, String to) throws TranslationException {
        if (word == null || word.isBlank()) throw new TranslationException("Type a word first.");
        if (!valid(from) || !valid(to)) throw new TranslationException("Choose two supported languages.");
        word = word.trim();
        if (from.equals(to)) return word;

        String key = from.equals("English") ? word.toLowerCase() : null;
        if (key == null) {
            for (Map.Entry<String, Map<String, String>> e : dict.entrySet())
                if (e.getValue().get(from).equals(word)) { key = e.getKey(); break; }
        }
        if (key == null || !dict.containsKey(key))
            throw new TranslationException("\"" + word + "\" is not in the dictionary yet. Try: " + dict.keySet());
        return to.equals("English") ? key : dict.get(key).get(to);
    }

    private boolean valid(String l) { return l != null && (l.equals("English") || LANGS.contains(l)); }
}

/* ======================= 4. MULTITHREADING + SYNCHRONIZATION ============== */
class ProgressTracker {                                         // shared by many request threads
    private final Map<Integer, int[]> totals = new HashMap<>(); // userId -> {points, total}
    synchronized void reset(int uid) { totals.put(uid, new int[2]); }
    synchronized void add(int uid, int points, int total) {
        int[] t = totals.computeIfAbsent(uid, k -> new int[2]);
        t[0] += points; t[1] += total;
    }
    synchronized int[] get(int uid) { return totals.getOrDefault(uid, new int[2]).clone(); }
}

/* ======================= 5. JDBC + DAO CLASSES ============================ */
class Db {
 static final String URL  = "jdbc:mysql://localhost:3306/langlearn?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String USER = "root";
    static final String PASS = "Mutki3131";                 // <-- change this
    static Connection get() throws SQLException { return DriverManager.getConnection(URL, USER, PASS); }
}

record UserRow(int id, String name, String role, int quizzes, int percent, String last) {
    Map<String, Object> map() {
        return Map.of("id", id, "name", name, "role", role, "quizzes", quizzes, "percent", percent, "last", last);
    }
}
record ScoreRow(String user, int score, int total, String at) {
    Map<String, Object> map() { return Map.of("user", user, "score", score, "total", total, "at", at); }
}

class UserDao implements Dao<UserRow, Integer> {
    void register(String u, String hash) throws SQLException {
        if (u == null || u.isBlank()) throw new IllegalArgumentException("Enter a username");
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("INSERT INTO users(username,password_hash) VALUES(?,?)")) {
            ps.setString(1, u); ps.setString(2, hash);
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new IllegalArgumentException("Username already taken");
        }
    }
    Person login(String u, String hash) throws SQLException {
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("SELECT id,username,role FROM users WHERE username=? AND password_hash=?")) {
            ps.setString(1, u); ps.setString(2, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Person.of(rs.getInt(1), rs.getString(2), rs.getString(3));
            }
        }
        return null;
    }
    @Override public List<UserRow> findAll() throws SQLException {
        List<UserRow> list = new ArrayList<>();
        try (Connection c = Db.get(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT u.id,u.username,u.role,COUNT(s.id),COALESCE(ROUND(100*SUM(s.score)/NULLIF(SUM(s.total),0)),0),MAX(s.taken_at) " +
                 "FROM users u LEFT JOIN scores s ON s.user_id=u.id GROUP BY u.id,u.username,u.role ORDER BY u.id")) {
            while (rs.next()) {
                String last = rs.getString(6);
                list.add(new UserRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), last == null ? "-" : last));
            }
        }
        return list;
    }
    @Override public void delete(Integer id) throws SQLException {   // scores go too (ON DELETE CASCADE)
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id=? AND role<>'admin'")) {
            ps.setInt(1, id); ps.executeUpdate();
        }
    }
}

class ScoreDao implements Dao<ScoreRow, Integer> {
    private final ReentrantLock lock = new ReentrantLock();      // one insert at a time
    private static final String SELECT =
        "SELECT u.username,s.score,s.total,s.taken_at FROM scores s JOIN users u ON u.id=s.user_id ";

    void add(int uid, int score, int total) throws SQLException {
        lock.lock();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("INSERT INTO scores(user_id,score,total) VALUES(?,?,?)")) {
            ps.setInt(1, uid); ps.setInt(2, score); ps.setInt(3, total);
            ps.executeUpdate();
        } finally { lock.unlock(); }
    }
    List<ScoreRow> findByUser(int uid) throws SQLException { return query(SELECT + "WHERE u.id=" + uid + " ORDER BY s.taken_at DESC"); }
    @Override public List<ScoreRow> findAll() throws SQLException { return query(SELECT + "ORDER BY s.taken_at DESC"); }
    private List<ScoreRow> query(String sql) throws SQLException {
        List<ScoreRow> list = new ArrayList<>();
        try (Connection c = Db.get(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(new ScoreRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4)));
        }
        return list;
    }
}

class StateDao implements Dao<String[], String> {
    @Override public List<String[]> findAll() throws SQLException {
        List<String[]> list = new ArrayList<>();
        try (Connection c = Db.get(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name,language FROM states ORDER BY name")) {
            while (rs.next()) list.add(new String[]{rs.getString(1), rs.getString(2)});
        }
        return list;
    }
}

/* ======================= 6. JSON HELPER ================================== */
class Json {
    static String of(Object o) {
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            for (Map.Entry<?, ?> e : m.entrySet()) j.add(of(e.getKey().toString()) + ":" + of(e.getValue()));
            return j.toString();
        }
        if (o instanceof Collection<?> c) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (Object x : c) j.add(of(x));
            return j.toString();
        }
        StringBuilder b = new StringBuilder("\"");
        for (char ch : o.toString().toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return b.append('"').toString();
    }
}

/* ======================= 7. SERVER + API ================================= */
public class LanguageApp {
    static final UserDao users = new UserDao();
    static final ScoreDao scores = new ScoreDao();
    static final StateDao states = new StateDao();
    static final ProgressTracker tracker = new ProgressTracker();
    static final Translatable translator = new DictionaryTranslator();
    static final Map<String, Person> sessions = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/", LanguageApp::api);
        server.createContext("/", LanguageApp::file);
        server.setExecutor(Executors.newFixedThreadPool(8));      // 8 worker threads
        server.start();
        System.out.println("Open http://localhost:8080");
    }

    static void api(HttpExchange ex) throws IOException {
        int code = 200;
        Object out;
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            Person me = sessions.get(q.getOrDefault("token", ""));
            out = route(ex.getRequestURI().getPath(), q, me);
        } catch (TranslationException | IllegalArgumentException e) {
            code = 400; out = Map.of("error", String.valueOf(e.getMessage()));
        } catch (SecurityException e) {
            code = 403; out = Map.of("error", String.valueOf(e.getMessage()));
        } catch (SQLException e) {
            code = 500; out = Map.of("error", "Database error: " + e.getMessage());
        } catch (RuntimeException e) {
            code = 500; out = Map.of("error", "Server error: " + e);
        }
        send(ex, code, "application/json; charset=UTF-8", Json.of(out));
    }

    static Object route(String path, Map<String, String> q, Person me) throws SQLException, TranslationException {
        switch (path) {
            case "/api/register":
                users.register(q.get("u"), hash(q.get("p")));
                return Map.of("ok", true);
            case "/api/login": {
                Person p = users.login(q.get("u"), hash(q.get("p")));
                if (p == null) throw new IllegalArgumentException("Wrong username or password");
                String t = UUID.randomUUID().toString();
                sessions.put(t, p);
                tracker.reset(p.id);
                for (ScoreRow r : scores.findByUser(p.id)) tracker.add(p.id, r.score(), r.total());
                return Map.of("token", t, "role", p.role());
            }
            case "/api/states": {
                need(me);
                List<Map<String, Object>> list = new ArrayList<>();
                for (String[] s : states.findAll()) list.add(Map.of("state", s[0], "language", s[1]));
                return Map.of("states", list);
            }
            case "/api/quiz":
                need(me);
                return Map.of("questions", quiz(Integer.parseInt(q.getOrDefault("n", "5"))));
            case "/api/score": {
                need(me);
                int s = Integer.parseInt(q.get("score")), t = Integer.parseInt(q.get("total"));
                if (s < 0 || t <= 0 || s > t) throw new IllegalArgumentException("Invalid score");
                scores.add(me.id, s, t);
                tracker.add(me.id, s, t);
                return Map.of("ok", true);
            }
            case "/api/progress": {
                need(me);
                List<Map<String, Object>> a = new ArrayList<>();
                for (ScoreRow r : scores.findByUser(me.id)) a.add(r.map());
                int[] t = tracker.get(me.id);
                return Map.of("attempts", a, "points", t[0], "total", t[1]);
            }
            case "/api/translate":
                need(me);
                return Map.of("result", translator.translate(q.get("word"), q.get("from"), q.get("to")));
            case "/api/admin/users":
                admin(me);
                return Map.of("users", users.findAll().stream().map(UserRow::map).toList());
            case "/api/admin/delete":
                admin(me);
                users.delete(Integer.valueOf(q.get("id")));
                return Map.of("ok", true);
            case "/api/admin/records":
                admin(me);
                return Map.of("records", scores.findAll().stream().map(ScoreRow::map).toList());
            default:
                throw new IllegalArgumentException("Unknown API: " + path);
        }
    }

    static List<Map<String, Object>> quiz(int n) throws SQLException {
        List<String[]> all = new ArrayList<>(states.findAll());
        List<String> langs = new ArrayList<>(new TreeSet<>(all.stream().map(s -> s[1]).toList()));
        Collections.shuffle(all);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] s : all.subList(0, Math.min(n, all.size()))) {
            List<String> wrong = new ArrayList<>(langs);
            wrong.remove(s[1]);
            Collections.shuffle(wrong);
            List<String> opts = new ArrayList<>(wrong.subList(0, Math.min(3, wrong.size())));
            opts.add(s[1]);
            Collections.shuffle(opts);
            out.add(Map.of("q", "Which language is mainly spoken in " + s[0] + "?", "options", opts, "answer", s[1]));
        }
        return out;
    }

    static void need(Person me) { if (me == null) throw new SecurityException("Please log in first"); }
    static void admin(Person me) { need(me); if (!me.canManageUsers()) throw new SecurityException("Admins only"); }

    static String hash(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Enter username and password");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                             URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void file(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        if (!p.equals("/index.html") && !p.equals("/style.css")) { send(ex, 404, "text/plain", "Not found"); return; }
        String type = p.endsWith(".css") ? "text/css; charset=UTF-8" : "text/html; charset=UTF-8";
        send(ex, 200, type, new String(Files.readAllBytes(Paths.get("." + p)), StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }
}
