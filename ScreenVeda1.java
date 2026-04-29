import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.security.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;

/**
 * ScreenVeda — Windows Screen Time Tracker
 * Java 8 compatible.
 *
 * ── Dependencies ─────────────────────────────────────────────────
 *   sqlite-jdbc-3.36.0.3.jar
 *
 * ── Compile ──────────────────────────────────────────────────────
 *   javac -cp .;sqlite-jdbc-3.36.0.3.jar ScreenVeda.java
 *
 * ── Run ──────────────────────────────────────────────────────────
 *   java -cp .;sqlite-jdbc-3.36.0.3.jar ScreenVeda
 *
 * ── Auto-start on boot ───────────────────────────────────────────
 *   Creates a .vbs launcher in
 *   %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup
 */
public class ScreenVeda1 {

    // ── App data directory ────────────────────────────────────────────────────
    static final String APP_DATA_DIR = resolveAppDataDir();
    static final String DB_PATH      = APP_DATA_DIR + File.separator + "screenveda.db";

    static String resolveAppDataDir() {
        String appData = System.getenv("APPDATA");
        String dir = (appData != null ? appData : System.getProperty("user.home"))
                     + File.separator + "ScreenVeda";
        new File(dir).mkdirs();
        return dir;
    }

    // ── Colour palette (dark theme) ───────────────────────────────────────────
    static final Color BG_DARK    = new Color(15,  17,  23);
    static final Color BG_CARD    = new Color(22,  25,  35);
    static final Color BG_ROW_ALT = new Color(28,  32,  44);
    static final Color ACCENT     = new Color(99,  102, 241);
    static final Color ACCENT2    = new Color(16,  185, 129);
    static final Color ACCENT3    = new Color(245, 158,  11);
    static final Color ACCENT4    = new Color(239,  68,  68);
    static final Color TEXT_PRI   = new Color(241, 241, 245);
    static final Color TEXT_SEC   = new Color(140, 143, 162);
    static final Color BORDER     = new Color(38,  42,  58);
    static final Color INPUT_BG   = new Color(30,  33,  48);

    static final Color[] BAR_COLORS = {
        new Color(99,102,241), new Color(16,185,129), new Color(245,158,11),
        new Color(239,68,68),  new Color(168,85,247), new Color(236,72,153),
        new Color(14,165,233), new Color(34,197,94)
    };

    // ── Current logged-in user ────────────────────────────────────────────────
    static volatile String CURRENT_USER = null;

    // =========================================================================
    // MAIN
    // =========================================================================
    public static void main(String[] args) throws Exception {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        Class.forName("org.sqlite.JDBC");

        final DatabaseManager db = new DatabaseManager();
        db.init();

        AutoStart.install();

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                } catch (Exception ignored) {}
                showLoginWindow(db);
            }
        });
    }

    static void showLoginWindow(final DatabaseManager db) {
        LoginWindow lw = new LoginWindow(db, new LoginWindow.LoginCallback() {
            public void onLoginSuccess(String username) {
                CURRENT_USER = username;
                launchMainApp(db);
            }
        });
        lw.setVisible(true);
    }

    static void launchMainApp(final DatabaseManager db) {
        final NotificationService notif = new NotificationService(db);
        final BackgroundService   svc   = new BackgroundService(db, notif);

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() { svc.stop(); }
        }));
        svc.start();

        new MainWindow(db, svc).setVisible(true);
    }

    // =========================================================================
    // WINDOW / PROCESS DETECTION — Windows only (PowerShell)
    // =========================================================================

    static String getActiveWindowTitle() {
        try {
            String r = shell("powershell -NoProfile -Command \"" +
                "$fw = (Add-Type -PassThru -Name FF2 -Namespace VV2 -MemberDefinition " +
                "'[DllImport(\\\"user32\\\")]public static extern IntPtr GetForegroundWindow();')" +
                "::GetForegroundWindow();" +
                "$proc = Get-Process | Where-Object {$_.MainWindowHandle -eq $fw} | Select -First 1;" +
                "if($proc){$proc.MainWindowTitle}else{'idle'}\"");
            return (r != null && !r.trim().isEmpty()) ? r.trim() : "idle";
        } catch (Exception e) { return "idle"; }
    }

    static String getActiveProcessName() {
        try {
            String r = shell("powershell -NoProfile -Command \"" +
                "$fw = (Add-Type -PassThru -Name FF -Namespace VV -MemberDefinition " +
                "'[DllImport(\\\"user32\\\")]public static extern IntPtr GetForegroundWindow();')" +
                "::GetForegroundWindow();" +
                "$proc = Get-Process | Where-Object {$_.MainWindowHandle -eq $fw} | Select -First 1;" +
                "if($proc){$proc.Name}else{'unknown'}\"");
            if (r == null || r.trim().isEmpty()) return "unknown";
            String name = r.trim();
            if (name.contains(".")) name = name.substring(0, name.indexOf("."));
            return name;
        } catch (Exception e) { return "unknown"; }
    }

    // On Windows the OS handles lock/sleep; if the app is running the user is active.
    static boolean isUserActive() { return true; }

    // ── Shell helper ──────────────────────────────────────────────────────────
    static String shell(String cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            line = br.readLine();
        }
        p.waitFor(5, TimeUnit.SECONDS);
        return line;
    }

    // =========================================================================
    // AUTO-START INSTALLER
    // =========================================================================
    static class AutoStart {
        static void install() {
            try {
                String jarPath = new File(ScreenVeda1.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();

                String startupDir = System.getenv("APPDATA") +
                    "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup";
                File vbs = new File(startupDir, "ScreenVeda.vbs");
                if (vbs.exists()) return;

                String javaExe = System.getProperty("java.home") + "\\bin\\javaw.exe";
                String cp = jarPath;
                File dir = new File(jarPath).getParentFile();
                if (dir != null) {
                    File[] jars = dir.listFiles(new FilenameFilter() {
                        public boolean accept(File d, String n) {
                            return n.startsWith("sqlite-jdbc") && n.endsWith(".jar");
                        }
                    });
                    if (jars != null && jars.length > 0) cp = cp + ";" + jars[0].getAbsolutePath();
                }
                String script =
                    "Set objShell = CreateObject(\"WScript.Shell\")\r\n" +
                    "objShell.Run \"\\\"" + javaExe + "\\\" -cp \\\"" + cp + "\\\" ScreenVeda\", 0, False\r\n";
                try (FileWriter fw = new FileWriter(vbs)) { fw.write(script); }
                System.out.println("[AutoStart] Startup entry created: " + vbs.getAbsolutePath());
            } catch (Exception e) {
                System.out.println("[AutoStart] Could not install: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // SECURITY HELPERS  (SHA-256 password hashing)
    // =========================================================================
    static String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt + password).getBytes("UTF-8"));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String generateSalt() {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        StringBuilder sb = new StringBuilder();
        for (byte b : salt) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // =========================================================================
    // LOGIN WINDOW
    // =========================================================================
    static class LoginWindow extends JFrame {
        interface LoginCallback { void onLoginSuccess(String username); }

        private final DatabaseManager db;
        private final LoginCallback   callback;

        private JPanel    cardPanel;
        private CardLayout cards;

        private JTextField     loginUser;
        private JPasswordField loginPass;
        private JLabel         loginError;

        private JTextField     regUser;
        private JPasswordField regPass;
        private JPasswordField regPass2;
        private JTextField     regEmail;
        private JLabel         regError;

        LoginWindow(DatabaseManager db, LoginCallback callback) {
            this.db       = db;
            this.callback = callback;
            setTitle("ScreenVeda — Sign In");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(500, 720);
            setMinimumSize(new Dimension(500, 680));
            setLocationRelativeTo(null);
            setResizable(true);
            getContentPane().setBackground(BG_DARK);
            buildUI();
        }

        private void buildUI() {
            setLayout(new BorderLayout());

            JPanel banner = new JPanel();
            banner.setBackground(BG_DARK);
            banner.setLayout(new BoxLayout(banner, BoxLayout.Y_AXIS));
            banner.setBorder(BorderFactory.createEmptyBorder(36, 0, 24, 0));

            JLabel logoIcon = new JLabel("\u23F1");
            logoIcon.setFont(new Font("SansSerif", Font.PLAIN, 40));
            logoIcon.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel logoText = new JLabel("ScreenVeda");
            logoText.setFont(new Font("SansSerif", Font.BOLD, 26));
            logoText.setForeground(TEXT_PRI);
            logoText.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel logoSub = new JLabel("Screen Time Tracker");
            logoSub.setFont(new Font("SansSerif", Font.PLAIN, 13));
            logoSub.setForeground(TEXT_SEC);
            logoSub.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel osBadge = makeBadge("Windows", ACCENT);
            osBadge.setAlignmentX(Component.CENTER_ALIGNMENT);

            banner.add(logoIcon);
            banner.add(Box.createVerticalStrut(8));
            banner.add(logoText);
            banner.add(Box.createVerticalStrut(4));
            banner.add(logoSub);
            banner.add(Box.createVerticalStrut(8));
            banner.add(osBadge);

            cards     = new CardLayout();
            cardPanel = new JPanel(cards);
            cardPanel.setBackground(BG_DARK);
            cardPanel.add(buildLoginPanel(),    "login");
            cardPanel.add(buildRegisterPanel(), "register");

            add(banner,    BorderLayout.NORTH);
            add(cardPanel, BorderLayout.CENTER);
        }

        private JPanel buildLoginPanel() {
            JPanel p = new JPanel();
            p.setBackground(BG_DARK);
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBorder(BorderFactory.createEmptyBorder(0, 40, 20, 40));

            JPanel card = new JPanel();
            card.setBackground(BG_CARD);
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(new CompoundBorder(
                new RoundedBorder(BORDER, 16),
                BorderFactory.createEmptyBorder(28, 28, 28, 28)
            ));

            JLabel title = new JLabel("Welcome back");
            title.setFont(new Font("SansSerif", Font.BOLD, 18));
            title.setForeground(TEXT_PRI);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel sub = new JLabel("Sign in to your account");
            sub.setFont(new Font("SansSerif", Font.PLAIN, 13));
            sub.setForeground(TEXT_SEC);
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);

            loginUser = makeField("Username");
            loginPass = makePasswordField("Password");

            loginError = new JLabel(" ");
            loginError.setFont(new Font("SansSerif", Font.PLAIN, 12));
            loginError.setForeground(ACCENT4);
            loginError.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton btnLogin = new AccentButton("Sign In", ACCENT);
            btnLogin.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            btnLogin.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
            switchRow.setBackground(BG_CARD);
            switchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel noAcc = new JLabel("Don't have an account?");
            noAcc.setForeground(TEXT_SEC);
            noAcc.setFont(new Font("SansSerif", Font.PLAIN, 12));
            JButton switchBtn = makeLinkButton("Create one");
            switchRow.add(noAcc);
            switchRow.add(switchBtn);

            card.add(title);
            card.add(Box.createVerticalStrut(4));
            card.add(sub);
            card.add(Box.createVerticalStrut(20));
            card.add(makeLabel("Username"));
            card.add(Box.createVerticalStrut(4));
            card.add(loginUser);
            card.add(Box.createVerticalStrut(12));
            card.add(makeLabel("Password"));
            card.add(Box.createVerticalStrut(4));
            card.add(loginPass);
            card.add(Box.createVerticalStrut(8));
            card.add(loginError);
            card.add(Box.createVerticalStrut(12));
            card.add(btnLogin);
            card.add(Box.createVerticalStrut(16));
            card.add(switchRow);

            p.add(card);

            btnLogin.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { doLogin(); }
            });
            loginPass.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { doLogin(); }
            });
            switchBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { cards.show(cardPanel, "register"); }
            });

            return p;
        }

        private JPanel buildRegisterPanel() {
            JPanel p = new JPanel();
            p.setBackground(BG_DARK);
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBorder(BorderFactory.createEmptyBorder(0, 48, 32, 48));

            JPanel card = new JPanel();
            card.setBackground(BG_CARD);
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(new CompoundBorder(
                new RoundedBorder(BORDER, 16),
                BorderFactory.createEmptyBorder(28, 28, 28, 28)
            ));

            JLabel title = new JLabel("Create account");
            title.setFont(new Font("SansSerif", Font.BOLD, 18));
            title.setForeground(TEXT_PRI);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel sub = new JLabel("Start tracking your screen time");
            sub.setFont(new Font("SansSerif", Font.PLAIN, 13));
            sub.setForeground(TEXT_SEC);
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);

            regUser  = makeField("Choose a username");
            regEmail = makeField("Email (optional)");
            regPass  = makePasswordField("Password (min 6 chars)");
            regPass2 = makePasswordField("Confirm password");

            regError = new JLabel(" ");
            regError.setFont(new Font("SansSerif", Font.PLAIN, 12));
            regError.setForeground(ACCENT4);
            regError.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton btnReg = new AccentButton("Create Account", ACCENT2);
            btnReg.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            btnReg.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
            switchRow.setBackground(BG_CARD);
            switchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel hasAcc = new JLabel("Already have an account?");
            hasAcc.setForeground(TEXT_SEC);
            hasAcc.setFont(new Font("SansSerif", Font.PLAIN, 12));
            JButton switchBtn = makeLinkButton("Sign in");
            switchRow.add(hasAcc);
            switchRow.add(switchBtn);

            card.add(title);
            card.add(Box.createVerticalStrut(4));
            card.add(sub);
            card.add(Box.createVerticalStrut(20));
            card.add(makeLabel("Username"));
            card.add(Box.createVerticalStrut(4));
            card.add(regUser);
            card.add(Box.createVerticalStrut(10));
            card.add(makeLabel("Email (optional)"));
            card.add(Box.createVerticalStrut(4));
            card.add(regEmail);
            card.add(Box.createVerticalStrut(10));
            card.add(makeLabel("Password"));
            card.add(Box.createVerticalStrut(4));
            card.add(regPass);
            card.add(Box.createVerticalStrut(10));
            card.add(makeLabel("Confirm Password"));
            card.add(Box.createVerticalStrut(4));
            card.add(regPass2);
            card.add(Box.createVerticalStrut(8));
            card.add(regError);
            card.add(Box.createVerticalStrut(12));
            card.add(btnReg);
            card.add(Box.createVerticalStrut(16));
            card.add(switchRow);

            p.add(card);

            btnReg.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { doRegister(); }
            });
            switchBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { cards.show(cardPanel, "login"); }
            });

            return p;
        }

        void doLogin() {
            String user = loginUser.getText().trim();
            String pass = new String(loginPass.getPassword());
            if (user.isEmpty() || pass.isEmpty()) {
                loginError.setText("Please enter username and password.");
                return;
            }
            try {
                String[] row = db.getUser(user);
                if (row == null) {
                    loginError.setText("No account found for \"" + user + "\".");
                    return;
                }
                String storedHash = row[0];
                String salt       = row[1];
                if (!hashPassword(pass, salt).equals(storedHash)) {
                    loginError.setText("Incorrect password. Please try again.");
                    return;
                }
                loginError.setText(" ");
                dispose();
                callback.onLoginSuccess(user);
            } catch (Exception ex) {
                loginError.setText("Error: " + ex.getMessage());
            }
        }

        void doRegister() {
            String user  = regUser.getText().trim();
            String email = regEmail.getText().trim();
            String pass  = new String(regPass.getPassword());
            String pass2 = new String(regPass2.getPassword());

            if (user.isEmpty())      { regError.setText("Username cannot be empty."); return; }
            if (user.length() < 3)   { regError.setText("Username must be at least 3 characters."); return; }
            if (pass.length() < 6)   { regError.setText("Password must be at least 6 characters."); return; }
            if (!pass.equals(pass2)) { regError.setText("Passwords do not match."); return; }

            try {
                if (db.userExists(user)) {
                    regError.setText("Username \"" + user + "\" is already taken.");
                    return;
                }
                String salt = generateSalt();
                String hash = hashPassword(pass, salt);
                db.createUser(user, hash, salt, email);
                regError.setText(" ");
                loginUser.setText(user);
                loginError.setText("Account created! Please sign in.");
                loginError.setForeground(ACCENT2);
                cards.show(cardPanel, "login");
            } catch (Exception ex) {
                regError.setText("Error: " + ex.getMessage());
            }
        }

        JTextField makeField(String placeholder) {
            JTextField f = new JTextField() {
                public void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    if (getText().isEmpty() && !isFocusOwner()) {
                        Graphics2D g2 = (Graphics2D) g;
                        g2.setColor(TEXT_SEC);
                        g2.setFont(getFont().deriveFont(Font.ITALIC));
                        g2.drawString(placeholder, 10, getHeight() / 2 + 5);
                    }
                }
            };
            styleInput(f);
            f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            f.setAlignmentX(Component.LEFT_ALIGNMENT);
            return f;
        }

        JPasswordField makePasswordField(String placeholder) {
            JPasswordField f = new JPasswordField() {
                public void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    if (getPassword().length == 0 && !isFocusOwner()) {
                        Graphics2D g2 = (Graphics2D) g;
                        g2.setColor(TEXT_SEC);
                        g2.setFont(getFont().deriveFont(Font.ITALIC));
                        g2.drawString(placeholder, 10, getHeight() / 2 + 5);
                    }
                }
            };
            styleInput(f);
            f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            f.setAlignmentX(Component.LEFT_ALIGNMENT);
            return f;
        }

        void styleInput(JTextField f) {
            f.setBackground(INPUT_BG);
            f.setForeground(TEXT_PRI);
            f.setCaretColor(TEXT_PRI);
            f.setFont(new Font("SansSerif", Font.PLAIN, 13));
            f.setBorder(new CompoundBorder(
                new RoundedBorder(BORDER, 8),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
            ));
            f.addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { f.repaint(); }
                public void focusLost(FocusEvent e)   { f.repaint(); }
            });
        }

        JLabel makeLabel(String text) {
            JLabel l = new JLabel(text);
            l.setFont(new Font("SansSerif", Font.PLAIN, 12));
            l.setForeground(TEXT_SEC);
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            return l;
        }

        JButton makeLinkButton(String text) {
            JButton b = new JButton(text);
            b.setFont(new Font("SansSerif", Font.BOLD, 12));
            b.setForeground(ACCENT);
            b.setBackground(new Color(0,0,0,0));
            b.setOpaque(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setContentAreaFilled(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.setBorder(BorderFactory.createEmptyBorder());
            return b;
        }

        JLabel makeBadge(String text, Color color) {
            JLabel l = new JLabel(text) {
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 30));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, getHeight(), getHeight());
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            l.setFont(new Font("SansSerif", Font.BOLD, 11));
            l.setForeground(color);
            l.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            l.setOpaque(false);
            return l;
        }
    }

    // =========================================================================
    // MAIN WINDOW
    // =========================================================================
    static class MainWindow extends JFrame {

        private final DatabaseManager   db;
        private final BackgroundService svc;

        private DashboardPanel  dashPanel;
        private WeeklyPanel     weeklyPanel;
        private LimitsPanel     limitsPanel;
        private StatusBar       statusBar;
        private javax.swing.Timer refreshTimer;

        MainWindow(DatabaseManager db, BackgroundService svc) {
            this.db  = db;
            this.svc = svc;
            setTitle("ScreenVeda  \u2014  " + CURRENT_USER);
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(960, 680);
            setMinimumSize(new Dimension(820, 560));
            setLocationRelativeTo(null);
            getContentPane().setBackground(BG_DARK);
            buildUI();
            setupTray();
            setupTimer();
            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) { setVisible(false); }
            });
        }

        private void buildUI() {
            setLayout(new BorderLayout(0, 0));

            SideBar sidebar = new SideBar(new SideBar.NavListener() {
                public void onNav(int index) {}
            });

            dashPanel   = new DashboardPanel(db);
            weeklyPanel = new WeeklyPanel(db);
            limitsPanel = new LimitsPanel(db);

            JPanel content = new JPanel(new CardLayout());
            content.setBackground(BG_DARK);
            content.add(dashPanel,   "dash");
            content.add(weeklyPanel, "weekly");
            content.add(limitsPanel, "limits");

            sidebar.setContent(content);
            statusBar = new StatusBar(svc);

            add(sidebar,   BorderLayout.WEST);
            add(content,   BorderLayout.CENTER);
            add(statusBar, BorderLayout.SOUTH);
        }

        private void setupTimer() {
            refreshTimer = new javax.swing.Timer(3000, new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    dashPanel.refresh();
                    weeklyPanel.refresh();
                    statusBar.refresh();
                }
            });
            refreshTimer.start();
        }

        private void setupTray() {
            if (!SystemTray.isSupported()) return;
            try {
                Image img = createTrayIcon();
                TrayIcon icon = new TrayIcon(img, "ScreenVeda");
                icon.setImageAutoSize(true);
                PopupMenu menu = new PopupMenu();
                MenuItem show = new MenuItem("Show ScreenVeda");
                show.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { setVisible(true); toFront(); }
                });
                MenuItem quit = new MenuItem("Quit");
                quit.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { System.exit(0); }
                });
                menu.add(show); menu.addSeparator(); menu.add(quit);
                icon.setPopupMenu(menu);
                icon.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { setVisible(true); toFront(); }
                });
                SystemTray.getSystemTray().add(icon);
            } catch (Exception ignored) {}
        }

        private Image createTrayIcon() {
            int sz = 16;
            java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ACCENT);
            g.fillRoundRect(0, 0, sz, sz, 4, 4);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("S", (sz - fm.stringWidth("S")) / 2, (sz + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
            return img;
        }
    }

    // =========================================================================
    // SIDEBAR
    // =========================================================================
    static class SideBar extends JPanel {

        interface NavListener { void onNav(int index); }

        private final NavListener listener;
        private final JButton[]   navBtns = new JButton[3];
        private       JPanel      content;
        private       CardLayout  cards;
        private       int         active  = 0;

        SideBar(NavListener listener) {
            this.listener = listener;
            setPreferredSize(new Dimension(200, 0));
            setBackground(BG_CARD);
            setLayout(new BorderLayout());
            setBorder(new MatteBorder(0, 0, 0, 1, BORDER));

            JPanel top = new JPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
            top.setBackground(BG_CARD);
            top.setBorder(BorderFactory.createEmptyBorder(24, 0, 16, 0));

            JLabel logo = new JLabel("ScreenVeda");
            logo.setFont(new Font("SansSerif", Font.BOLD, 16));
            logo.setForeground(TEXT_PRI);
            logo.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel userLbl = new JLabel(CURRENT_USER != null ? "\uD83D\uDC64 " + CURRENT_USER : "");
            userLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
            userLbl.setForeground(ACCENT2);
            userLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

            top.add(logo);
            top.add(Box.createVerticalStrut(4));
            top.add(userLbl);
            top.add(Box.createVerticalStrut(24));

            String[] labels = { "  Dashboard", "  Weekly", "  Limits" };
            String[] icons  = { "\uD83D\uDCCA", "\uD83D\uDCC5", "\u23F1" };
            for (int i = 0; i < 3; i++) {
                navBtns[i] = makeNavBtn(icons[i] + labels[i], i);
                top.add(navBtns[i]);
                top.add(Box.createVerticalStrut(4));
            }
            setActive(0);

            add(top, BorderLayout.NORTH);

            JLabel ver = new JLabel("v1.1  \u2022  Windows");
            ver.setFont(new Font("SansSerif", Font.PLAIN, 10));
            ver.setForeground(new Color(70, 75, 95));
            ver.setHorizontalAlignment(SwingConstants.CENTER);
            ver.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
            add(ver, BorderLayout.SOUTH);
        }

        void setContent(JPanel c) {
            this.content = c;
            this.cards   = (CardLayout) c.getLayout();
        }

        private JButton makeNavBtn(String text, final int idx) {
            JButton btn = new JButton(text) {
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (active == idx) {
                        g2.setColor(new Color(99, 102, 241, 40));
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    } else if (getModel().isRollover()) {
                        g2.setColor(new Color(255, 255, 255, 10));
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    }
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            btn.setFont(new Font("SansSerif", Font.PLAIN, 13));
            btn.setForeground(active == idx ? ACCENT : TEXT_SEC);
            btn.setBackground(new Color(0, 0, 0, 0));
            btn.setOpaque(false); btn.setBorderPainted(false);
            btn.setFocusPainted(false); btn.setContentAreaFilled(false);
            btn.setHorizontalAlignment(SwingConstants.LEFT);
            btn.setMaximumSize(new Dimension(188, 40));
            btn.setPreferredSize(new Dimension(188, 40));
            btn.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    setActive(idx);
                    if (content != null) {
                        String[] names = {"dash","weekly","limits"};
                        cards.show(content, names[idx]);
                    }
                    listener.onNav(idx);
                }
            });
            return btn;
        }

        void setActive(int idx) {
            active = idx;
            for (int i = 0; i < navBtns.length; i++) {
                if (navBtns[i] == null) continue;
                navBtns[i].setForeground(i == idx ? ACCENT : TEXT_SEC);
                navBtns[i].repaint();
            }
        }
    }

    // =========================================================================
    // DASHBOARD PANEL
    // =========================================================================
    static class DashboardPanel extends JPanel {

        private final DatabaseManager db;
        private StatCard[]       statCards;
        private UsageTablePanel  tablePanel;
        private MiniBarChart     miniChart;

        DashboardPanel(DatabaseManager db) {
            this.db = db;
            setBackground(BG_DARK);
            setLayout(new BorderLayout(0, 0));
            setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            build(); refresh();
        }

        private void build() {
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(BG_DARK);
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));

            JLabel title = new JLabel("Today's Overview");
            title.setFont(new Font("SansSerif", Font.BOLD, 22));
            title.setForeground(TEXT_PRI);

            JLabel date = new JLabel(new SimpleDateFormat("EEEE, d MMMM yyyy").format(new Date()));
            date.setFont(new Font("SansSerif", Font.PLAIN, 13));
            date.setForeground(TEXT_SEC);

            header.add(title, BorderLayout.WEST);
            header.add(date,  BorderLayout.EAST);

            JPanel cardsRow = new JPanel(new GridLayout(1, 3, 12, 0));
            cardsRow.setBackground(BG_DARK);
            cardsRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));

            statCards = new StatCard[]{
                new StatCard("Total Screen Time", "0m", ACCENT,  "\uD83D\uDD50"),
                new StatCard("Apps Used",         "0",  ACCENT2, "\uD83D\uDCF1"),
                new StatCard("Most Used",         "\u2014", ACCENT3, "\uD83C\uDFC6")
            };
            for (StatCard c : statCards) cardsRow.add(c);

            tablePanel = new UsageTablePanel();
            miniChart  = new MiniBarChart();

            JPanel bottom = new JPanel(new BorderLayout(16, 0));
            bottom.setBackground(BG_DARK);
            bottom.add(tablePanel, BorderLayout.CENTER);
            bottom.add(miniChart,  BorderLayout.EAST);

            add(header,   BorderLayout.NORTH);
            add(cardsRow, BorderLayout.CENTER);
            add(bottom,   BorderLayout.SOUTH);
        }

        void refresh() {
            try {
                Map<String, Long> usage = db.getDailyUsage(CURRENT_USER);
                long totalSecs = 0;
                for (Long v : usage.values()) totalSecs += v;
                statCards[0].setValue(NotificationService.fmt(totalSecs));
                statCards[1].setValue(String.valueOf(usage.size()));
                if (!usage.isEmpty()) {
                    Map.Entry<String, Long> top = usage.entrySet().iterator().next();
                    statCards[2].setValue(top.getKey() + " (" + NotificationService.fmt(top.getValue()) + ")");
                }
                tablePanel.setData(usage, db);
                miniChart.setData(usage);
            } catch (Exception e) {
                System.err.println("[UI] refresh error: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // STAT CARD
    // =========================================================================
    static class StatCard extends JPanel {

        private final JLabel valueLabel;
        private final Color  accent;

        StatCard(String title, String value, Color accent, String icon) {
            this.accent = accent;
            setBackground(BG_CARD);
            setLayout(new BorderLayout());
            setBorder(new CompoundBorder(
                new RoundedBorder(BORDER, 12),
                BorderFactory.createEmptyBorder(18, 20, 18, 20)
            ));

            JLabel iconLabel = new JLabel(icon);
            iconLabel.setFont(new Font("SansSerif", Font.PLAIN, 24));

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
            titleLabel.setForeground(TEXT_SEC);

            valueLabel = new JLabel(value);
            valueLabel.setFont(new Font("SansSerif", Font.BOLD, 20));
            valueLabel.setForeground(accent);

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setBackground(BG_CARD);
            text.add(titleLabel);
            text.add(Box.createVerticalStrut(6));
            text.add(valueLabel);

            add(text, BorderLayout.CENTER);
            add(iconLabel, BorderLayout.EAST);
        }

        void setValue(String v) { valueLabel.setText(v); }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(3));
            g2.drawLine(20, 0, 80, 0);
            g2.dispose();
        }
    }

    // =========================================================================
    // USAGE TABLE
    // =========================================================================
    static class UsageTablePanel extends JPanel {

        private final DefaultTableModel model;
        private final JTable            table;

        UsageTablePanel() {
            setBackground(BG_CARD);
            setLayout(new BorderLayout());
            setBorder(new CompoundBorder(new RoundedBorder(BORDER, 12),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)));

            JLabel heading = new JLabel("  App Usage Today");
            heading.setFont(new Font("SansSerif", Font.BOLD, 13));
            heading.setForeground(TEXT_PRI);
            heading.setBorder(BorderFactory.createEmptyBorder(14, 14, 10, 0));
            heading.setOpaque(true);
            heading.setBackground(BG_CARD);

            model = new DefaultTableModel(new String[]{"App", "Time", "Share", ""}, 0) {
                public boolean isCellEditable(int r, int c) { return false; }
                public Class<?> getColumnClass(int c) {
                    return c == 2 ? Double.class : String.class;
                }
            };

            table = new JTable(model) {
                public Component prepareRenderer(TableCellRenderer r, int row, int col) {
                    Component c = super.prepareRenderer(r, row, col);
                    c.setBackground(row % 2 == 0 ? BG_CARD : BG_ROW_ALT);
                    c.setForeground(col == 0 ? TEXT_PRI : TEXT_SEC);
                    if (isRowSelected(row)) {
                        c.setBackground(new Color(99, 102, 241, 60));
                        c.setForeground(TEXT_PRI);
                    }
                    return c;
                }
            };
            table.setBackground(BG_CARD); table.setForeground(TEXT_PRI);
            table.setGridColor(BORDER); table.setRowHeight(34);
            table.setFont(new Font("SansSerif", Font.PLAIN, 13));
            table.setShowVerticalLines(false);
            table.setFocusable(false);
            table.getTableHeader().setBackground(BG_CARD);
            table.getTableHeader().setForeground(TEXT_SEC);
            table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
            table.getTableHeader().setBorder(new MatteBorder(0, 0, 1, 0, BORDER));
            table.getTableHeader().setReorderingAllowed(false);
            table.getColumnModel().getColumn(2).setCellRenderer(new ProgressBarRenderer());
            table.getColumnModel().getColumn(0).setPreferredWidth(160);
            table.getColumnModel().getColumn(1).setPreferredWidth(100);
            table.getColumnModel().getColumn(2).setPreferredWidth(120);
            table.getColumnModel().getColumn(3).setPreferredWidth(50);

            JScrollPane scroll = new JScrollPane(table);
            scroll.setBackground(BG_CARD);
            scroll.getViewport().setBackground(BG_CARD);
            scroll.setBorder(BorderFactory.createEmptyBorder());

            add(heading, BorderLayout.NORTH);
            add(scroll,  BorderLayout.CENTER);
            setPreferredSize(new Dimension(0, 280));
        }

        void setData(Map<String, Long> usage, DatabaseManager db) {
            model.setRowCount(0);
            long total = 0;
            for (Long v : usage.values()) total += v;
            if (total == 0) return;
            for (Map.Entry<String, Long> e : usage.entrySet()) {
                double pct = (double) e.getValue() / total * 100.0;
                String limitStr = "";
                try {
                    long lim = db.getLimit(CURRENT_USER, e.getKey());
                    if (lim > 0) limitStr = "/ " + NotificationService.fmt(lim);
                } catch (Exception ignored) {}
                model.addRow(new Object[]{
                    e.getKey(),
                    NotificationService.fmt(e.getValue()) + " " + limitStr,
                    pct,
                    String.format("%.0f%%", pct)
                });
            }
        }
    }

    // =========================================================================
    // PROGRESS BAR RENDERER
    // =========================================================================
    static class ProgressBarRenderer extends DefaultTableCellRenderer {
        private double value = 0;

        ProgressBarRenderer() { setOpaque(true); }

        public Component getTableCellRendererComponent(JTable t, Object v,
                boolean sel, boolean focus, int row, int col) {
            value = v instanceof Double ? (Double) v : 0;
            setBackground(row % 2 == 0 ? BG_CARD : BG_ROW_ALT);
            if (sel) setBackground(new Color(99, 102, 241, 40));
            return this;
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 16, h = 8, x = 8, y = (getHeight() - h) / 2;
            g2.setColor(BORDER);
            g2.fillRoundRect(x, y, w, h, h, h);
            int fill = (int)(w * value / 100.0);
            if (fill > 0) {
                Color c = value > 80 ? ACCENT4 : value > 50 ? ACCENT3 : ACCENT;
                g2.setColor(c);
                g2.fillRoundRect(x, y, fill, h, h, h);
            }
            g2.dispose();
        }
    }

    // =========================================================================
    // MINI BAR CHART
    // =========================================================================
    static class MiniBarChart extends JPanel {

        private Map<String, Long> data = new LinkedHashMap<String, Long>();

        MiniBarChart() {
            setBackground(BG_CARD);
            setPreferredSize(new Dimension(220, 280));
            setBorder(new CompoundBorder(new RoundedBorder(BORDER, 12),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)));
        }

        void setData(Map<String, Long> d) { this.data = d; repaint(); }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 28, h = getHeight() - 28, x0 = 14, y0 = 14;
            g2.setFont(new Font("SansSerif", Font.BOLD, 13));
            g2.setColor(TEXT_PRI);
            g2.drawString("Top Apps", x0, y0 + 14);
            if (data.isEmpty()) {
                g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
                g2.setColor(TEXT_SEC);
                g2.drawString("No data yet", x0 + 20, y0 + h / 2);
                g2.dispose(); return;
            }
            List<Map.Entry<String,Long>> entries = new ArrayList<Map.Entry<String,Long>>(data.entrySet());
            if (entries.size() > 6) entries = entries.subList(0, 6);
            long max = 0;
            for (Map.Entry<String,Long> e : entries) if (e.getValue() > max) max = e.getValue();
            if (max == 0) { g2.dispose(); return; }
            int chartY = y0 + 32, chartH = h - 42;
            int barH = Math.max(8, (chartH / entries.size()) - 6);
            int gap  = entries.size() > 1 ? (chartH - barH * entries.size()) / (entries.size() - 1) : 0;
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String,Long> e = entries.get(i);
                int barY = chartY + i * (barH + gap);
                int barW = (int)((double) e.getValue() / max * (w - 10));
                Color c = BAR_COLORS[i % BAR_COLORS.length];
                g2.setColor(BORDER); g2.fillRoundRect(x0, barY, w - 10, barH, barH, barH);
                if (barW > 0) { g2.setColor(c); g2.fillRoundRect(x0, barY, barW, barH, barH, barH); }
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.setColor(TEXT_SEC);
                String label = e.getKey();
                if (label.length() > 14) label = label.substring(0, 12) + "\u2026";
                g2.drawString(label, x0, barY - 2);
            }
            g2.dispose();
        }
    }

    // =========================================================================
    // WEEKLY PANEL
    // =========================================================================
    static class WeeklyPanel extends JPanel {

        private final DatabaseManager db;
        private WeeklyBarChart chart;
        private JLabel         totalLabel;

        WeeklyPanel(DatabaseManager db) {
            this.db = db;
            setBackground(BG_DARK);
            setLayout(new BorderLayout(0, 16));
            setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            build(); refresh();
        }

        private void build() {
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(BG_DARK);
            JLabel title = new JLabel("Weekly Trends");
            title.setFont(new Font("SansSerif", Font.BOLD, 22));
            title.setForeground(TEXT_PRI);
            totalLabel = new JLabel("Last 7 days");
            totalLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
            totalLabel.setForeground(TEXT_SEC);
            header.add(title, BorderLayout.WEST);
            header.add(totalLabel, BorderLayout.EAST);
            chart = new WeeklyBarChart();
            add(header, BorderLayout.NORTH);
            add(chart,  BorderLayout.CENTER);
        }

        void refresh() {
            try {
                Map<String, Long> weekly = db.getWeeklyUsage(CURRENT_USER, 7);
                chart.setData(weekly);
                long total = 0;
                for (Long v : weekly.values()) total += v;
                totalLabel.setText("Total: " + NotificationService.fmt(total) + " in 7 days");
            } catch (Exception e) {
                System.err.println("[UI] weekly refresh: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // WEEKLY BAR CHART
    // =========================================================================
    static class WeeklyBarChart extends JPanel {

        private Map<String, Long> data = new LinkedHashMap<String, Long>();

        WeeklyBarChart() {
            setBackground(BG_CARD);
            setPreferredSize(new Dimension(600, 400));
            setBorder(new CompoundBorder(new RoundedBorder(BORDER, 12),
                BorderFactory.createEmptyBorder(20, 20, 20, 20)));
        }

        void setData(Map<String, Long> d) { this.data = d; repaint(); }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 80, h = getHeight() - 80, x0 = 50, y0 = 20;
            if (data.isEmpty()) {
                g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
                g2.setColor(TEXT_SEC);
                g2.drawString("No data yet \u2014 start using apps to see trends", x0 + 40, y0 + h / 2);
                g2.dispose(); return;
            }
            long max = 0;
            for (Long v : data.values()) if (v > max) max = v;
            if (max == 0) { g2.dispose(); return; }
            g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 0, new float[]{4,4}, 0));
            int gridLines = 5;
            for (int i = 0; i <= gridLines; i++) {
                int y = y0 + h - (int)((double) i / gridLines * h);
                g2.setColor(BORDER); g2.drawLine(x0, y, x0 + w, y);
                g2.setColor(TEXT_SEC); g2.setStroke(new BasicStroke(1));
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.drawString(NotificationService.fmt(max * i / gridLines), 2, y + 4);
                g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 0, new float[]{4,4}, 0));
            }
            g2.setStroke(new BasicStroke(1));
            List<Map.Entry<String,Long>> entries = new ArrayList<Map.Entry<String,Long>>(data.entrySet());
            int n = entries.size();
            int barW = Math.max(20, (w - 10) / Math.max(n, 1) - 12);
            int spacing = (w - barW * n) / Math.max(n + 1, 1);
            for (int i = 0; i < n; i++) {
                Map.Entry<String,Long> e = entries.get(i);
                int bx = x0 + spacing + i * (barW + spacing);
                int bh = (int)((double) e.getValue() / max * h);
                int by = y0 + h - bh;
                Color c = i % 3 == 1 ? ACCENT2 : i % 3 == 2 ? ACCENT3 : ACCENT;
                g2.setColor(new Color(0,0,0,40)); g2.fillRoundRect(bx+3, by+3, barW, bh, 6, 6);
                g2.setColor(c); g2.fillRoundRect(bx, by, barW, bh, 6, 6);
                if (bh > 20) {
                    g2.setFont(new Font("SansSerif", Font.BOLD, 10));
                    g2.setColor(TEXT_PRI);
                    String val = NotificationService.fmt(e.getValue());
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(val, bx + (barW - fm.stringWidth(val)) / 2, by - 4);
                }
                g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
                g2.setColor(TEXT_SEC);
                String day = e.getKey().length() >= 10 ? e.getKey().substring(5) : e.getKey();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(day, bx + (barW - fm.stringWidth(day)) / 2, y0 + h + 18);
            }
            g2.setColor(BORDER); g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(x0, y0 + h, x0 + w, y0 + h);
            g2.dispose();
        }
    }

    // =========================================================================
    // LIMITS PANEL
    // =========================================================================
    static class LimitsPanel extends JPanel {

        private final DatabaseManager db;
        private DefaultTableModel model;
        private JTable            table;

        LimitsPanel(DatabaseManager db) {
            this.db = db;
            setBackground(BG_DARK);
            setLayout(new BorderLayout(0, 12));
            setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            build(); refreshTable();
        }

        private void build() {
            JLabel title = new JLabel("Usage Limits");
            title.setFont(new Font("SansSerif", Font.BOLD, 22));
            title.setForeground(TEXT_PRI);

            JPanel info = new JPanel(new BorderLayout());
            info.setBackground(new Color(99, 102, 241, 25));
            info.setBorder(new CompoundBorder(new RoundedBorder(ACCENT, 10),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
            JLabel infoText = new JLabel("Set daily time limits per app. You'll get a notification when the limit is reached.");
            infoText.setFont(new Font("SansSerif", Font.PLAIN, 13));
            infoText.setForeground(new Color(165, 168, 255));
            info.add(infoText, BorderLayout.CENTER);

            model = new DefaultTableModel(new String[]{"App", "Daily Limit", "Remove"}, 0) {
                public boolean isCellEditable(int r, int c) { return c == 2; }
            };

            table = new JTable(model) {
                public Component prepareRenderer(TableCellRenderer r, int row, int col) {
                    Component c = super.prepareRenderer(r, row, col);
                    c.setBackground(row % 2 == 0 ? BG_CARD : BG_ROW_ALT);
                    c.setForeground(TEXT_PRI); return c;
                }
            };
            table.setBackground(BG_CARD); table.setForeground(TEXT_PRI);
            table.setGridColor(BORDER); table.setRowHeight(36);
            table.setFont(new Font("SansSerif", Font.PLAIN, 13));
            table.setShowVerticalLines(false); table.setFocusable(false);
            table.getTableHeader().setBackground(BG_CARD);
            table.getTableHeader().setForeground(TEXT_SEC);
            table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
            table.getTableHeader().setBorder(new MatteBorder(0, 0, 1, 0, BORDER));
            table.getColumnModel().getColumn(0).setPreferredWidth(200);
            table.getColumnModel().getColumn(1).setPreferredWidth(150);
            table.getColumnModel().getColumn(2).setPreferredWidth(80);
            table.getColumnModel().getColumn(2).setCellRenderer(new ButtonRenderer("Remove", ACCENT4));
            table.getColumnModel().getColumn(2).setCellEditor(new ButtonEditor(table, db, this));

            JScrollPane scroll = new JScrollPane(table);
            scroll.setBackground(BG_CARD);
            scroll.getViewport().setBackground(BG_CARD);
            scroll.setBorder(new RoundedBorder(BORDER, 12));

            JPanel form = buildAddForm();

            JPanel top = new JPanel(new BorderLayout(0, 10));
            top.setBackground(BG_DARK);
            top.add(title, BorderLayout.NORTH);
            top.add(info,  BorderLayout.CENTER);

            add(top,    BorderLayout.NORTH);
            add(scroll, BorderLayout.CENTER);
            add(form,   BorderLayout.SOUTH);
        }

        private JPanel buildAddForm() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            panel.setBackground(BG_DARK);
            panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

            JLabel appLbl = makeFormLabel("App name:");
            final JTextField appField = new DarkTextField(14);
            appField.setToolTipText("e.g. chrome, firefox, notepad");

            JLabel hrLbl  = makeFormLabel("Hours:");
            final JSpinner hrSpin  = new JSpinner(new SpinnerNumberModel(1, 0, 23, 1));
            styleSpinner(hrSpin);

            JLabel minLbl = makeFormLabel("Minutes:");
            final JSpinner minSpin = new JSpinner(new SpinnerNumberModel(0, 0, 59, 5));
            styleSpinner(minSpin);

            JButton addBtn = new AccentButton("Set Limit", ACCENT);
            addBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    String app = appField.getText().trim();
                    if (app.isEmpty()) {
                        JOptionPane.showMessageDialog(LimitsPanel.this, "Please enter an app name.", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    int hrs  = (Integer) hrSpin.getValue();
                    int mins = (Integer) minSpin.getValue();
                    long secs = hrs * 3600L + mins * 60L;
                    if (secs == 0) {
                        JOptionPane.showMessageDialog(LimitsPanel.this, "Limit must be > 0.", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    try {
                        db.setLimit(CURRENT_USER, app, secs);
                        appField.setText("");
                        hrSpin.setValue(1); minSpin.setValue(0);
                        refreshTable();
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(LimitsPanel.this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });

            panel.add(appLbl); panel.add(appField);
            panel.add(hrLbl);  panel.add(hrSpin);
            panel.add(minLbl); panel.add(minSpin);
            panel.add(addBtn);
            return panel;
        }

        void refreshTable() {
            try {
                model.setRowCount(0);
                Map<String, Long> limits = db.getAllLimits(CURRENT_USER);
                for (Map.Entry<String,Long> e : limits.entrySet())
                    model.addRow(new Object[]{e.getKey(), NotificationService.fmt(e.getValue()), "Remove"});
            } catch (Exception e) {
                System.err.println("[UI] limits refresh: " + e.getMessage());
            }
        }

        JLabel makeFormLabel(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(TEXT_SEC);
            l.setFont(new Font("SansSerif", Font.PLAIN, 13));
            return l;
        }

        void styleSpinner(JSpinner s) {
            s.setPreferredSize(new Dimension(60, 28));
            s.setBackground(BG_CARD); s.setForeground(TEXT_PRI);
            JComponent editor = s.getEditor();
            if (editor instanceof JSpinner.DefaultEditor) {
                JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
                tf.setBackground(BG_CARD); tf.setForeground(TEXT_PRI);
                tf.setCaretColor(TEXT_PRI);
                tf.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            }
        }
    }

    // =========================================================================
    // BUTTON CELL RENDERER / EDITOR
    // =========================================================================
    static class ButtonRenderer extends DefaultTableCellRenderer {
        private final String label; private final Color color;
        ButtonRenderer(String label, Color color) { this.label = label; this.color = color; }
        public Component getTableCellRendererComponent(JTable t, Object v,
                boolean sel, boolean focus, int row, int col) {
            JButton btn = new AccentButton(label, color);
            btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
            return btn;
        }
    }

    static class ButtonEditor extends DefaultCellEditor {
        private final JTable          table;
        private final DatabaseManager db;
        private final LimitsPanel     panel;
        private       JButton         btn;
        private       String          app;

        ButtonEditor(JTable table, DatabaseManager db, LimitsPanel panel) {
            super(new JTextField());
            this.table = table; this.db = db; this.panel = panel;
            btn = new AccentButton("Remove", ACCENT4);
            btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
            btn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    fireEditingStopped();
                    try { db.removeLimit(CURRENT_USER, app); panel.refreshTable(); }
                    catch (Exception ex) { System.err.println("remove limit: " + ex.getMessage()); }
                }
            });
        }

        public Component getTableCellEditorComponent(JTable t, Object v,
                boolean sel, int row, int col) {
            app = (String) t.getValueAt(row, 0); return btn;
        }
        public Object getCellEditorValue() { return "Remove"; }
    }

    // =========================================================================
    // STATUS BAR
    // =========================================================================
    static class StatusBar extends JPanel {

        private final BackgroundService svc;
        private final JLabel currentApp;
        private final JLabel timeLabel;

        StatusBar(BackgroundService svc) {
            this.svc = svc;
            setBackground(BG_CARD);
            setLayout(new BorderLayout());
            setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));
            setPreferredSize(new Dimension(0, 34));

            currentApp = new JLabel("\u25CF Tracking");
            currentApp.setFont(new Font("SansSerif", Font.PLAIN, 12));
            currentApp.setForeground(ACCENT2);

            JLabel modeLabel = new JLabel("Windows");
            modeLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
            modeLabel.setForeground(TEXT_SEC);

            timeLabel = new JLabel("");
            timeLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
            timeLabel.setForeground(TEXT_SEC);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
            right.setBackground(BG_CARD);
            right.add(modeLabel); right.add(timeLabel);

            add(currentApp, BorderLayout.WEST);
            add(right,      BorderLayout.EAST);
            refresh();
        }

        void refresh() {
            String app = svc.getCurrentApp();
            if (app == null || app.isEmpty()) {
                currentApp.setText("\u25CF Idle");
                currentApp.setForeground(TEXT_SEC);
            } else {
                currentApp.setText("\u25CF " + app);
                currentApp.setForeground(ACCENT2);
            }
            timeLabel.setText(new SimpleDateFormat("HH:mm:ss").format(new Date()));
        }
    }

    // =========================================================================
    // WIDGET HELPERS
    // =========================================================================
    static class RoundedBorder extends AbstractBorder {
        private final Color color; private final int radius;
        RoundedBorder(Color c, int r) { color = c; radius = r; }
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.drawRoundRect(x, y, w-1, h-1, radius, radius);
            g2.dispose();
        }
        public Insets getBorderInsets(Component c) { return new Insets(radius/2,radius/2,radius/2,radius/2); }
    }

    static class DarkTextField extends JTextField {
        DarkTextField(int cols) {
            super(cols);
            setBackground(INPUT_BG); setForeground(TEXT_PRI);
            setCaretColor(TEXT_PRI);
            setFont(new Font("SansSerif", Font.PLAIN, 13));
            setBorder(new CompoundBorder(new RoundedBorder(BORDER, 8),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        }
    }

    static class AccentButton extends JButton {
        AccentButton(String text, Color accent) {
            super(text);
            setBackground(accent); setForeground(Color.WHITE);
            setFont(new Font("SansSerif", Font.BOLD, 12));
            setFocusPainted(false); setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
            setOpaque(true);
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getModel().isPressed() ? getBackground().darker()
                      : getModel().isRollover() ? getBackground().brighter() : getBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // =========================================================================
    // BACKGROUND SERVICE
    // =========================================================================
    static class BackgroundService {

        private final DatabaseManager          db;
        private final NotificationService      notif;
        private       ScheduledExecutorService sched;

        private volatile String curApp   = "";
        private volatile String curTitle = "";
        private          long   curStart = 0L;

        BackgroundService(DatabaseManager db, NotificationService notif) {
            this.db = db; this.notif = notif;
        }

        String getCurrentApp() { return curApp; }

        void start() {
            sched = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "screenveda-bg");
                    t.setDaemon(true); return t;
                }
            });
            sched.scheduleAtFixedRate(new Runnable() {
                public void run() { tick(); }
            }, 0, 1, TimeUnit.SECONDS);
            System.out.println("[ScreenVeda] Tracker started (Windows)");
        }

        void stop() {
            if (sched != null) { sched.shutdownNow(); flush(true); }
        }

        private void tick() {
            try {
                String app   = clean(ScreenVeda1.getActiveProcessName());
                String title = clean(ScreenVeda1.getActiveWindowTitle());

                if (app.equals("unknown") || app.equals("idle")) return;

                if (!app.equals(curApp)) {
                    flush(true);
                    curApp = app; curTitle = title; curStart = System.currentTimeMillis();
                }
                if (notif != null) notif.checkLimit(app);
                long elapsed = (System.currentTimeMillis() - curStart) / 1000;
                if (elapsed > 0 && elapsed % 60 == 0) {
                    flush(true);
                    curApp = app; curTitle = title; curStart = System.currentTimeMillis();
                }
            } catch (Exception e) {
                System.err.println("[ScreenVeda] tick: " + e.getMessage());
            }
        }

        private void flush(boolean reset) {
            if (curApp.isEmpty() || curStart == 0L) return;
            int secs = (int)((System.currentTimeMillis() - curStart) / 1000);
            if (secs > 0) db.insertSession(CURRENT_USER, curApp, curTitle, curStart / 1000L, secs);
            if (reset) { curApp = ""; curTitle = ""; curStart = 0L; }
        }

        static String clean(String s) {
            return (s == null || s.trim().isEmpty()) ? "unknown" : s.trim();
        }
    }

    // =========================================================================
    // DATABASE MANAGER
    // =========================================================================
    static class DatabaseManager {

        private Connection conn;

        void init() throws SQLException {
            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS users (" +
                    "username TEXT PRIMARY KEY," +
                    "password_hash TEXT NOT NULL," +
                    "salt TEXT NOT NULL," +
                    "email TEXT," +
                    "created_at INTEGER DEFAULT (strftime('%s','now')))");
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS app_usage (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "username TEXT NOT NULL," +
                    "app_name TEXT NOT NULL," +
                    "window TEXT," +
                    "start_time INTEGER NOT NULL," +
                    "duration_s INTEGER NOT NULL," +
                    "date TEXT NOT NULL DEFAULT (date('now')))");
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS usage_limits (" +
                    "username TEXT NOT NULL," +
                    "app_name TEXT NOT NULL," +
                    "limit_s INTEGER NOT NULL," +
                    "PRIMARY KEY (username, app_name))");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_usage_date ON app_usage(username, date)");
            }
            System.out.println("[ScreenVeda] DB ready: " + DB_PATH);
        }

        synchronized boolean userExists(String username) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM users WHERE username=?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        }

        synchronized void createUser(String username, String hash, String salt, String email)
                throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users(username,password_hash,salt,email) VALUES(?,?,?,?)")) {
                ps.setString(1, username); ps.setString(2, hash);
                ps.setString(3, salt);    ps.setString(4, email.isEmpty() ? null : email);
                ps.executeUpdate();
            }
        }

        synchronized String[] getUser(String username) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT password_hash, salt FROM users WHERE username=?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new String[]{ rs.getString(1), rs.getString(2) };
                }
            }
        }

        synchronized void insertSession(String username, String app, String window,
                long startEpoch, int durSecs) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO app_usage(username,app_name,window,start_time,duration_s) VALUES(?,?,?,?,?)")) {
                ps.setString(1, username); ps.setString(2, app);
                ps.setString(3, window != null ? window : "");
                ps.setLong(4, startEpoch); ps.setInt(5, durSecs);
                ps.executeUpdate();
            } catch (SQLException e) { System.err.println("[DB] insert: " + e.getMessage()); }
        }

        synchronized Map<String, Long> getDailyUsage(String username) throws SQLException {
            Map<String, Long> map = new LinkedHashMap<String, Long>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT app_name,SUM(duration_s) AS t FROM app_usage " +
                    "WHERE username=? AND date=date('now') GROUP BY app_name ORDER BY t DESC")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) map.put(rs.getString("app_name"), rs.getLong("t"));
                }
            }
            return map;
        }

        synchronized Map<String, Long> getWeeklyUsage(String username, int days) throws SQLException {
            Map<String, Long> map = new LinkedHashMap<String, Long>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT date,SUM(duration_s) AS t FROM app_usage " +
                    "WHERE username=? AND date>=date('now',?) GROUP BY date ORDER BY date")) {
                ps.setString(1, username);
                ps.setString(2, "-" + days + " days");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) map.put(rs.getString("date"), rs.getLong("t"));
                }
            }
            return map;
        }

        synchronized long getLimit(String username, String app) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT limit_s FROM usage_limits WHERE username=? AND app_name=?")) {
                ps.setString(1, username); ps.setString(2, app);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        }

        synchronized Map<String, Long> getAllLimits(String username) throws SQLException {
            Map<String, Long> map = new LinkedHashMap<String, Long>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT app_name,limit_s FROM usage_limits WHERE username=? ORDER BY app_name")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) map.put(rs.getString("app_name"), rs.getLong("limit_s"));
                }
            }
            return map;
        }

        synchronized void setLimit(String username, String app, long limitSecs) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO usage_limits(username,app_name,limit_s) VALUES(?,?,?) " +
                    "ON CONFLICT(username,app_name) DO UPDATE SET limit_s=excluded.limit_s")) {
                ps.setString(1, username); ps.setString(2, app); ps.setLong(3, limitSecs);
                ps.executeUpdate();
            }
        }

        synchronized void removeLimit(String username, String app) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM usage_limits WHERE username=? AND app_name=?")) {
                ps.setString(1, username); ps.setString(2, app);
                ps.executeUpdate();
            }
        }
    }

    // =========================================================================
    // NOTIFICATION SERVICE
    // =========================================================================
    static class NotificationService {

        private final DatabaseManager     db;
        private final Map<String,Boolean> fired = new ConcurrentHashMap<String,Boolean>();

        NotificationService(DatabaseManager db) { this.db = db; }

        void checkLimit(String app) {
            try {
                long lim = db.getLimit(CURRENT_USER, app);
                if (lim <= 0) return;
                Map<String,Long> usage = db.getDailyUsage(CURRENT_USER);
                long used = usage.containsKey(app) ? usage.get(app) : 0L;
                Boolean f = fired.get(app);
                if (used >= lim && (f == null || !f)) {
                    send("ScreenVeda: limit reached!",
                        app + " \u2014 used " + fmt(used) + " (limit: " + fmt(lim) + ")");
                    fired.put(app, true);
                }
                if (used < lim) fired.remove(app);
            } catch (Exception e) { System.err.println("[Notif] " + e.getMessage()); }
        }

        private static void send(String title, String body) {
            System.out.println("[ALERT] " + title + " | " + body);
            try {
                String ps =
                    "[Windows.UI.Notifications.ToastNotificationManager," +
                    "Windows.UI.Notifications,ContentType=WindowsRuntime]|Out-Null;" +
                    "$t='<toast><visual><binding template=\\\"ToastGeneric\\\">" +
                    "<text>" + title.replace("'","") + "</text>" +
                    "<text>" + body.replace("'","") + "</text>" +
                    "</binding></visual></toast>';" +
                    "$x=[Windows.Data.Xml.Dom.XmlDocument,Windows.Data.Xml.Dom," +
                    "ContentType=WindowsRuntime]::new();$x.LoadXml($t);" +
                    "[Windows.UI.Notifications.ToastNotificationManager]" +
                    "::CreateToastNotifier('ScreenVeda')" +
                    ".Show([Windows.UI.Notifications.ToastNotification]::new($x))";
                new ProcessBuilder("powershell", "-NoProfile", "-Command", ps).start();
            } catch (Exception ignored) {}
        }

        static String fmt(long seconds) {
            long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
            if (h > 0) return h + "h " + m + "m";
            if (m > 0) return m + "m " + s + "s";
            return s + "s";
        }
    }
}