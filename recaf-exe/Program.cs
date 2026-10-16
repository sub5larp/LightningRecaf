// LightningRecaf Windows application entry point.

using System;
using System.Collections;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using LightningRecaf.Updates;
using Microsoft.Win32;

[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8", FrameworkDisplayName = ".NET Framework 4.8")]
[assembly: AssemblyTitle("LightningRecaf")]
[assembly: AssemblyProduct("LightningRecaf")]
[assembly: AssemblyDescription("LightningRecaf Java reverse engineering workbench")]
[assembly: AssemblyVersion("2.4.0.0")]
[assembly: AssemblyFileVersion("2.4.0.0")]

namespace RecafExe
{
    static class Program
    {
        internal static readonly string WindowTitle = "LightningRecaf - " + Assembly.GetExecutingAssembly().GetName().Version.ToString(3);
        [STAThread]
        static int Main(string[] args)
        {
            if (args.Length == 1 && args[0] == "--update-check") {
                AppUpdates.Check(Paths.LightningDir, AppUpdates.CurrentVersion);
                return 0;
            }
            string entry = AppUpdates.EntryExecutable(Application.ExecutablePath);
            if (AppUpdates.TryLaunchReady(Paths.LightningDir, AppUpdates.CurrentVersion, entry, args)) return 0;
            Log.Init(Paths.ExeLog);
            Log.Info("LightningRecaf.exe started, args: [" + string.Join(", ", args) + "]");
            try
            {
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072 | (SecurityProtocolType)12288; // TLS 1.2 | 1.3
            }
            catch (NotSupportedException)
            {
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            ShellIntegration.SetProcessAppId();

            var options = LaunchOptions.Parse(args);
            if (options.InputError != null) {
                MessageBox.Show(options.InputError, "LightningRecaf", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
            var config = ExeConfig.Load();
            if (options.Uninstall)
            {
                ShellIntegration.Uninstall();
                MessageBox.Show("LightningRecaf shell integration removed (Open With, context menu, Start Menu shortcut, jump list).", "LightningRecaf",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }
            if (options.Install)
            {
                if (!ShellIntegration.EnsureInstalled(config, true)) {
                    MessageBox.Show("Shell registration failed. Details: " + Paths.ExeLog, "LightningRecaf", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 1;
                }
                MessageBox.Show("LightningRecaf shell integration installed:\n- Open With for jar/class/zip/war/ear/aar/apk/dex/jmod files\n- Explorer context menu\n" +
                                "- Start Menu shortcut (pin it to the taskbar from there)\n- Jump list with recent workspaces", "LightningRecaf",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }
            ShellIntegration.EnsureInstalled(config, false);

            AppUpdates.StartBackgroundCheck(Paths.LightningDir, entry, args);
            var splash = new SplashForm();
            splash.Title = "LightningRecaf";
            var worker = new LaunchWorker(options, splash);
            splash.Shown += delegate { new Thread(worker.Run) { IsBackground = true, Name = "recaf-launch" }.Start(); };
            // Errors are shown while the message loop is still running: once the main form has closed, a pending
            // WM_QUIT would dismiss any message box immediately.
            splash.BeforeClose = delegate
            {
                if (worker.FailureMessage == null) return;
                splash.Hide();
                ShowFailure(worker);
            };
            Application.Run(splash);
            return worker.FailureMessage == null ? 0 : 1;
        }

        static void ShowFailure(LaunchWorker worker)
        {
            Log.Info("Launch failed: " + worker.FailureMessage);
            if (worker.FailureLink != null)
            {
                var r = MessageBox.Show(worker.FailureMessage + "\n\nOpen the download page now?", "LightningRecaf",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Error);
                if (r == DialogResult.Yes)
                    try { System.Diagnostics.Process.Start(worker.FailureLink); } catch { }
            }
            else
            {
                MessageBox.Show(worker.FailureMessage + "\n\nLog: " + Paths.ExeLog, "LightningRecaf",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Launch flow
    // ------------------------------------------------------------------------------------------------

    class LaunchOptions
    {
        public bool Install;
        public bool Uninstall;
        public string FileToOpen;  // display name of the dropped / "Open with" file, if any
        public string InputError;
        public List<string> RecafArgs = new List<string>();

        public static LaunchOptions Parse(string[] args)
        {
            var o = new LaunchOptions();
            var rest = new List<string>();
            bool literal = false;
            foreach (var a in args)
            {
                if (literal) { rest.Add(a); continue; }
                if (a == "--") { literal = true; continue; }
                // Accept old shortcut flags, but always start LightningRecaf.
                if (new[] { "--no-update", "--latest", "--custom", "--enhanced", "--ask" }.Contains(a, StringComparer.OrdinalIgnoreCase)) continue;
                else if (a.Equals("--install", StringComparison.OrdinalIgnoreCase)) o.Install = true;
                else if (a.Equals("--uninstall", StringComparison.OrdinalIgnoreCase)) o.Uninstall = true;
                else rest.Add(a);
            }
            // A single existing file/folder (drag & drop onto the exe, "Open with") is opened as Recaf's input.
            if (rest.Count > 1 && rest.All(a => File.Exists(a) || Directory.Exists(a))) {
                o.InputError = "Open one primary archive at a time. Add supporting files from the workspace menu after opening it.";
                return o;
            }
            if (rest.Count == 1 && (literal || !rest[0].StartsWith("-")) && (File.Exists(rest[0]) || Directory.Exists(rest[0])))
            {
                o.RecafArgs.Add("-i");
                o.RecafArgs.Add(Path.GetFullPath(rest[0]));
                o.FileToOpen = Path.GetFileName(rest[0].TrimEnd('\\', '/'));
            }
            else
            {
                if (rest.Count == 1 && (literal || !rest[0].StartsWith("-"))) o.InputError = "Input file or folder was not found: " + rest[0];
                o.RecafArgs.AddRange(rest);
            }
            return o;
        }
    }

    class LaunchWorker
    {
        readonly LaunchOptions options;
        readonly SplashForm ui;
        public string FailureMessage;
        public string FailureLink;

        public LaunchWorker(LaunchOptions options, SplashForm ui)
        {
            this.options = options;
            this.ui = ui;
        }

        public void Run()
        {
            try
            {
                RunInner();
            }
            catch (Exception e)
            {
                Log.Error("Unexpected failure", e);
                FailureMessage = "LightningRecaf could not be started:\n" + e.Message;
            }
            ui.CloseFromWorker();
        }

        void RunInner()
        {
            var config = ExeConfig.Load();
            Directory.CreateDirectory(Paths.RecafDir);
            Directory.CreateDirectory(Paths.LauncherDir);

            CustomEngine.Validate();
            string engineJar = CustomEngine.Jar;
            using (var mutex = new Mutex(false, "Local\\LightningRecafPrepare"))
            {
                bool owned = false;
                try
                {
                    try { owned = mutex.WaitOne(TimeSpan.FromMinutes(3)); }
                    catch (AbandonedMutexException) { owned = true; }
                    if (!owned) {
                        FailureMessage = "Another LightningRecaf instance is still preparing. Try again shortly.";
                        return;
                    }

                    ui.SetStatus("Locating Java...", -1);
                    int requiredJava = EngineRequirements.RequiredJavaVersion(engineJar);
                    JavaInstall java = JavaLocator.Find(config.Get("java", null), requiredJava);
                    if (java == null)
                    {
                        if (Paths.IsPortable) {
                            FailureMessage = "The bundled Java runtime is missing or cannot run LightningRecaf (JDK " + requiredJava + "+ required).\n" +
                                             "Download the complete LightningRecaf EXE again, or restore the runtime and engine folders.";
                            return;
                        }
                        FailureMessage = "LightningRecaf needs a JDK " + requiredJava + " or newer, but none was found.\n" +
                                         "Install one (for example Temurin 25), or set 'java=' in:\n" + Paths.ExeConfig;
                        FailureLink = "https://adoptium.net/temurin/releases/?version=25&os=windows&arch=x64&package=jdk";
                        return;
                    }
                    Log.Info("Using Java " + java.Version + " at " + java.Home);

                    ui.SetStatus("Checking JavaFX...", -1);
                    string jfx = CustomEngine.JavaFxVersion();
                    if (jfx == null)
                    {
                        FailureMessage = "The bundled JavaFX files are missing. Restore the complete LightningRecaf package.";
                        return;
                    }
                    Log.Info("Using JavaFX " + jfx);

                    var recafArgs = new List<string>(options.RecafArgs);
                    ui.SetStatus("Preparing LightningRecaf...", -1);
                    string pluginDir = CustomPlugin.Ensure();
                    if (pluginDir == null)
                        throw new IOException("LightningRecaf's plugin is missing from this application. Rebuild the complete package.");
                    recafArgs.Insert(0, pluginDir);
                    recafArgs.Insert(0, "-r");

                    ui.SetStatus("Starting LightningRecaf...", -1);
                    RecafProcess.Start(java, jfx, config.Get("jvm-args", ""), recafArgs, ui, this);
                }
                finally
                {
                    if (owned) mutex.ReleaseMutex();
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // LightningRecaf plugin embedded in this executable
    // ------------------------------------------------------------------------------------------------

    static class CustomPlugin
    {
        const string ResourceName = "recaf-enhanced.jar";

        /// <summary>Writes the embedded plugin jar to the custom plugin folder (when changed) and returns that folder.</summary>
        public static string Ensure()
        {
            byte[] jar;
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName))
            {
                if (s == null) return null;
                using (var ms = new MemoryStream())
                {
                    s.CopyTo(ms);
                    jar = ms.ToArray();
                }
            }
            string pluginDir = Path.Combine(Paths.CustomPluginsDir, Sha256(jar));
            Directory.CreateDirectory(pluginDir);
            string target = Path.Combine(pluginDir, "LightningRecaf-plugin.jar");
            if (!File.Exists(target) || !Sha256(File.ReadAllBytes(target)).Equals(Sha256(jar), StringComparison.OrdinalIgnoreCase))
            {
                string tmp = target + ".tmp";
                File.WriteAllBytes(tmp, jar);
                try
                {
                    FileUtil.Replace(tmp, target);
                    Log.Info("LightningRecaf plugin written to " + target + " (" + jar.Length + " bytes)");
                }
                catch (IOException e)
                {
                    FileUtil.TryDelete(tmp);
                    throw new IOException("Cannot install the matching LightningRecaf plugin", e);
                }
            }
            return pluginDir;
        }

        static string Sha256(byte[] data)
        {
            using (var sha = SHA256.Create())
                return BitConverter.ToString(sha.ComputeHash(data)).Replace("-", "");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Paths, config, logging
    // ------------------------------------------------------------------------------------------------

    static class Paths
    {
        public static string ApplicationDirectory { get { return Path.GetDirectoryName(Application.ExecutablePath); } }
        public static bool IsPortable { get { return File.Exists(Path.Combine(ApplicationDirectory, "LightningRecaf.portable")); } }
        public static bool IsStandalone { get { return IsPortable && File.Exists(Path.Combine(ApplicationDirectory, "LightningRecaf.standalone")); } }
        public static string RuntimeDirectory { get { return Path.Combine(ApplicationDirectory, "runtime"); } }
        public static string RecafDir { get { return CustomDataDir; } }
        public static string LightningDir { get {
            return AppUpdates.DataDirectory(IsStandalone
                ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LightningRecaf", "data")
                : IsPortable ? Path.Combine(ApplicationDirectory, "data") : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "LightningRecaf"));
        } }
        public static string CustomDataDir { get { return Path.Combine(LightningDir, "custom"); } }
        public static string LauncherDir { get { return Path.Combine(LightningDir, "launcher"); } }
        public static string ExeConfig { get { return Path.Combine(LauncherDir, "recaf-exe.ini"); } }
        public static string ExeLog { get { return Path.Combine(LauncherDir, "recaf-exe-log.txt"); } }
        public static string RecafOutputLog { get { return Path.Combine(LauncherDir, "recaf-exe-output.txt"); } }
        public static string CustomPluginsDir { get { return Path.Combine(LauncherDir, "custom", "plugins"); } }

    }

    static class CustomEngine
    {
        public static string DirectoryPath
        {
            get
            {
                string beside = Path.Combine(Paths.ApplicationDirectory, "engine");
                if (Paths.IsPortable) return beside;
                if (File.Exists(Path.Combine(beside, "recaf.jar"))) return beside;
                return Path.GetFullPath(Path.Combine(Path.GetDirectoryName(Application.ExecutablePath), "..", "..", "engine"));
            }
        }
        public static string Jar { get { return Path.Combine(DirectoryPath, "recaf.jar"); } }
        public static void Validate() {
            string manifest=Path.Combine(DirectoryPath,"pin.json");
            if (!File.Exists(manifest)) throw new InvalidDataException("The custom engine pin.json is missing. Restore the complete LightningRecaf folder.");
            object root=Json.Parse(File.ReadAllText(manifest));
            string boundary=Path.GetFullPath(DirectoryPath).TrimEnd('\\')+"\\";
            int count=0;
            foreach(object entry in Json.Array(root,"files")) {
                string path=Path.GetFullPath(Path.Combine(boundary,Json.Str(entry,"path","")));
                if(!path.StartsWith(boundary,StringComparison.OrdinalIgnoreCase) || !File.Exists(path)) throw new InvalidDataException("Custom engine dependency is missing or invalid.");
                using(var stream=File.OpenRead(path))using(var hash=SHA256.Create()) {
                    string actual=BitConverter.ToString(hash.ComputeHash(stream)).Replace("-","").ToLowerInvariant();
                    if(actual!=Json.Str(entry,"sha256",""))throw new InvalidDataException("Pinned engine dependency changed: "+Path.GetFileName(path));
                }
                count++;
            }
            if(count!=5)throw new InvalidDataException("Incomplete custom engine manifest.");
            foreach(string overlay in new[] { "LightningRecaf-patches.jar", "LightningRecaf-branding.jar" })
                if(!File.Exists(Path.Combine(DirectoryPath,overlay)))
                    throw new InvalidDataException("Custom engine component is missing: "+overlay+". Restore the complete LightningRecaf folder.");
        }
        public static string Dependency(string name, string version) { return Path.Combine(DirectoryPath, "dependencies", name + "-" + version + "-win.jar"); }
        public static string JavaFxVersion()
        {
            string deps = Path.Combine(DirectoryPath, "dependencies");
            if (!Directory.Exists(deps)) return null;
            foreach (string file in Directory.GetFiles(deps, "javafx-controls-*-win.jar"))
            {
                string name = Path.GetFileName(file);
                string version = name.Substring("javafx-controls-".Length);
                version = version.Substring(0, version.Length - "-win.jar".Length);
                if (new[] { "javafx-base", "javafx-graphics", "javafx-controls", "javafx-media" }.All(m => File.Exists(Dependency(m, version)))) return version;
            }
            return null;
        }
    }

    class ExeConfig
    {
        const string DefaultContent =
            "# LightningRecaf.exe settings. Remove the leading '#' to enable a line.\r\n" +
            "#\r\n" +
            "# Java to run LightningRecaf with (javaw.exe, java.exe, or a JDK folder). Empty = auto-detect.\r\n" +
            "#java=\r\n" +
            "# Portable packages always use their bundled runtime.\r\n" +
            "#\r\n" +
            "# Extra JVM arguments, for example more memory.\r\n" +
            "#jvm-args=-Xmx4G\r\n" +
            "#\r\n" +
            "# Download new LightningRecaf releases in the background; use them on the next launch.\r\n" +
            "#auto-update=true\r\n" +
            "#\r\n" +
            "# Windows integration (portable packages require an explicit --install).\r\n" +
            "# Run 'LightningRecaf.exe --uninstall' to remove integration again.\r\n" +
            "#context-menu=true\r\n" +
            "#start-menu-shortcut=true\r\n";

        readonly Dictionary<string, string> values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        public static ExeConfig Load()
        {
            var c = new ExeConfig();
            try
            {
                if (!File.Exists(Paths.ExeConfig))
                {
                    Directory.CreateDirectory(Paths.LauncherDir);
                    File.WriteAllText(Paths.ExeConfig, DefaultContent);
                }
                foreach (var raw in File.ReadAllLines(Paths.ExeConfig))
                {
                    string line = raw.Trim();
                    if (line.Length == 0 || line.StartsWith("#") || line.StartsWith(";")) continue;
                    int eq = line.IndexOf('=');
                    if (eq <= 0) continue;
                    c.values[line.Substring(0, eq).Trim()] = line.Substring(eq + 1).Trim();
                }
            }
            catch (Exception e)
            {
                Log.Error("Could not read " + Paths.ExeConfig, e);
            }
            return c;
        }

        public string Get(string key, string def)
        {
            string v;
            return values.TryGetValue(key, out v) && v.Length > 0 ? v : def;
        }

        /// <summary>Writes a value back to the ini, keeping comments and other lines as they are.</summary>
        public void Set(string key, string value)
        {
            values[key] = value;
            try
            {
                var lines = File.Exists(Paths.ExeConfig) ? new List<string>(File.ReadAllLines(Paths.ExeConfig)) : new List<string>();
                bool replaced = false;
                for (int i = 0; i < lines.Count; i++)
                {
                    string t = lines[i].Trim();
                    if (t.StartsWith("#") || t.StartsWith(";")) continue;
                    int eq = t.IndexOf('=');
                    if (eq > 0 && t.Substring(0, eq).Trim().Equals(key, StringComparison.OrdinalIgnoreCase))
                    {
                        lines[i] = key + "=" + value;
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) lines.Add(key + "=" + value);
                File.WriteAllLines(Paths.ExeConfig, lines);
            }
            catch (Exception e)
            {
                Log.Error("Could not write " + Paths.ExeConfig, e);
            }
        }

        public bool GetBool(string key, bool def)
        {
            string v = Get(key, null);
            if (v == null) return def;
            return v.Equals("true", StringComparison.OrdinalIgnoreCase) || v == "1" || v.Equals("yes", StringComparison.OrdinalIgnoreCase);
        }
    }

    static class Log
    {
        static readonly object Lock = new object();
        static string file;

        public static void Init(string path)
        {
            file = path;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                File.WriteAllText(path, "");
            }
            catch { }
        }

        public static void Info(string msg) { Write("INFO ", msg); }

        public static void Error(string msg, Exception e) { Write("ERROR", e == null ? msg : msg + ": " + e); }

        static void Write(string level, string msg)
        {
            lock (Lock)
            {
                try { File.AppendAllText(file, DateTime.Now.ToString("HH:mm:ss.fff") + " [" + level + "] " + msg + Environment.NewLine); }
                catch { }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Networking / JSON helpers
    // ------------------------------------------------------------------------------------------------

    interface IProgressSink
    {
        // fraction < 0 means indeterminate.
        void SetStatus(string text, double fraction);
    }

    static class Json
    {
        public static object Parse(string text)
        {
            return new JavaScriptSerializer { MaxJsonLength = int.MaxValue }.DeserializeObject(text);
        }

        public static object Get(object obj, string key)
        {
            var map = obj as IDictionary<string, object>;
            object v;
            return map != null && map.TryGetValue(key, out v) ? v : null;
        }

        public static string Str(object obj, string key, string def)
        {
            var v = Get(obj, key) as string;
            return v ?? def;
        }

        public static long Long(object obj, string key, long def)
        {
            var v = Get(obj, key);
            if (v == null) return def;
            try { return Convert.ToInt64(v); } catch { return def; }
        }

        public static bool Bool(object obj, string key, bool def)
        {
            var v = Get(obj, key);
            return v is bool ? (bool)v : def;
        }

        public static IEnumerable<object> Array(object obj, string key)
        {
            var v = Get(obj, key) as IEnumerable;
            if (v == null || v is string) return Enumerable.Empty<object>();
            return v.Cast<object>();
        }
    }

    static class FileUtil
    {
        // Moves src over dest. Fails (IOException) if dest is locked, e.g. Recaf is already running from it.
        public static void Replace(string src, string dest)
        {
            if (File.Exists(dest))
            {
                try
                {
                    File.Replace(src, dest, null, true);
                    return;
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
                File.Delete(dest);
            }
            File.Move(src, dest);
        }

        public static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Bundled engine Java requirement.
    // ------------------------------------------------------------------------------------------------

    static class EngineRequirements
    {
        static ZipArchive OpenZip(string path) { return new ZipArchive(File.OpenRead(path), ZipArchiveMode.Read, false); }

        // Java version needed by the installed Recaf, from the class file version of its main class.
        public static int RequiredJavaVersion(string jar)
        {
            try
            {
                using (var zip = OpenZip(jar))
                {
                    var entry = zip.GetEntry("software/coley/recaf/Main.class");
                    if (entry != null)
                    {
                        var header = new byte[8];
                        using (var s = entry.Open())
                        {
                            int off = 0, n;
                            while (off < 8 && (n = s.Read(header, off, 8 - off)) > 0) off += n;
                        }
                        int major = (header[6] << 8) | header[7];
                        if (header[0] == 0xCA && header[1] == 0xFE && major >= 52) return major - 44;
                    }
                }
            }
            catch (Exception e)
            {
                Log.Error("Could not read class version from " + jar, e);
            }
            return 22;
        }

    }

    class JavaInstall
    {
        public string Home;
        public string Version;
        public int Major;
        public bool IsJdk;
        public string JavaExe { get { return Path.Combine(Home, "bin", "java.exe"); } }
    }

    static class JavaLocator
    {
        public static JavaInstall Find(string configured, int requiredMajor)
        {
            // A portable copy never falls through to another machine's Java or
            // remembered launcher settings, even if its bundled runtime is missing.
            if (Paths.IsPortable && !Native.JavaPathSupported(Paths.RuntimeDirectory))
                throw new IOException("The bundled Java runtime cannot start from this folder name on your Windows language settings. " +
                    "Move the complete LightningRecaf folder to a simple path such as C:\\Tools\\LightningRecaf, then launch it again. Your data folder can move with it.");
            var bundled = Probe(Paths.RuntimeDirectory);
            if (bundled != null && bundled.IsJdk && bundled.Major >= requiredMajor) return bundled;
            if (Paths.IsPortable) return null;
            // 1. Explicit setting in recaf-exe.ini
            if (configured != null)
            {
                var j = Probe(ToHome(configured));
                if (j != null && j.Major >= requiredMajor) return j;
                Log.Info("Configured java '" + configured + "' is not usable (needs JDK " + requiredMajor + "+), auto-detecting");
            }

            // Newest suitable JDK on the machine.
            var all = Candidates().Distinct(StringComparer.OrdinalIgnoreCase)
                .Select(Probe).Where(j => j != null).ToList();
            foreach (var j in all) Log.Info("Found Java " + j.Version + (j.IsJdk ? " (JDK) " : " (JRE) ") + j.Home);
            return all.Where(j => j.IsJdk && j.Major >= requiredMajor)
                .OrderByDescending(j => j.Major)
                .ThenByDescending(j => j.Version, StringComparer.Ordinal)
                .FirstOrDefault();
        }

        static string ToHome(string path)
        {
            path = path.Trim().Trim('"');
            if (File.Exists(path)) path = Path.GetDirectoryName(path); // ...\bin\java.exe -> ...\bin
            if (Path.GetFileName(path.TrimEnd('\\', '/')).Equals("bin", StringComparison.OrdinalIgnoreCase))
                path = Path.GetDirectoryName(path.TrimEnd('\\', '/'));
            return path;
        }

        static JavaInstall Probe(string home)
        {
            try
            {
                if (string.IsNullOrEmpty(home) || !File.Exists(Path.Combine(home, "bin", "java.exe"))) return null;
                string release = Path.Combine(home, "release");
                if (!File.Exists(release)) return null;
                var m = Regex.Match(File.ReadAllText(release), "^JAVA_VERSION=\"([^\"]+)\"", RegexOptions.Multiline);
                if (!m.Success) return null;
                string version = m.Groups[1].Value;
                var parts = version.Split('.', '-', '+', '_');
                int major = int.Parse(parts[0] == "1" && parts.Length > 1 ? parts[1] : parts[0]);
                return new JavaInstall
                {
                    Home = Path.GetFullPath(home).TrimEnd('\\'),
                    Version = version,
                    Major = major,
                    IsJdk = File.Exists(Path.Combine(home, "bin", "javac.exe")),
                };
            }
            catch { return null; }
        }

        static IEnumerable<string> Candidates()
        {
            var homes = new List<string>();
            string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(javaHome)) homes.Add(javaHome.Trim().Trim('"').TrimEnd('\\'));

            // PATH entries
            foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
            {
                try
                {
                    string d = dir.Trim().Trim('"');
                    if (d.Length > 0 && File.Exists(Path.Combine(d, "java.exe"))) homes.Add(ToHome(d));
                }
                catch { }
            }

            // Registry (vendor installers)
            AddRegistryHomes(homes, @"SOFTWARE\JavaSoft\JDK", null, "JavaHome");
            AddRegistryHomes(homes, @"SOFTWARE\Eclipse Adoptium\JDK", @"hotspot\MSI", "Path");
            AddRegistryHomes(homes, @"SOFTWARE\Eclipse Foundation\JDK", @"hotspot\MSI", "Path");
            AddRegistryHomes(homes, @"SOFTWARE\Microsoft\JDK", @"hotspot\MSI", "Path");
            AddRegistryHomes(homes, @"SOFTWARE\Azul Systems\Zulu", null, "InstallationPath");
            AddRegistryHomes(homes, @"SOFTWARE\BellSoft\Liberica", null, "InstallationPath");

            // Common install folders
            var roots = new[]
            {
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                Environment.GetEnvironmentVariable("ProgramW6432"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs"),
            };
            var vendors = new[]
            {
                "Java", "Eclipse Adoptium", "Eclipse Foundation", "AdoptOpenJDK", "Temurin", "Microsoft", "Zulu",
                "BellSoft", "Amazon Corretto", "Semeru", "GraalVM", "OpenJDK", "RedHat", "SapMachine",
            };
            foreach (var root in roots.Where(r => !string.IsNullOrEmpty(r)).Distinct(StringComparer.OrdinalIgnoreCase))
                foreach (var vendor in vendors)
                    AddSubdirs(homes, Path.Combine(root, vendor));

            string profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            AddSubdirs(homes, Path.Combine(profile, ".jdks")); // IntelliJ-managed JDKs
            return homes;
        }

        static void AddSubdirs(List<string> homes, string dir)
        {
            try
            {
                if (Directory.Exists(dir)) homes.AddRange(Directory.GetDirectories(dir));
            }
            catch { }
        }

        static void AddRegistryHomes(List<string> homes, string keyPath, string subKey, string valueName)
        {
            try
            {
                using (var key = Registry.LocalMachine.OpenSubKey(keyPath))
                {
                    if (key == null) return;
                    foreach (var name in key.GetSubKeyNames())
                    {
                        using (var ver = key.OpenSubKey(subKey == null ? name : name + "\\" + subKey))
                        {
                            var v = ver == null ? null : ver.GetValue(valueName) as string;
                            if (!string.IsNullOrEmpty(v)) homes.Add(v.TrimEnd('\\'));
                        }
                    }
                }
            }
            catch { }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Starting Recaf
    // ------------------------------------------------------------------------------------------------

    static class RecafProcess
    {
        const string MainClass = "software.coley.recaf.Main";

        internal static string BuildCommandLine(JavaInstall java, string jfxVersion, string jvmArgs,
                                                List<string> recafArgs)
        {
            var classpath = new List<string>();
            classpath.Add(Path.Combine(CustomEngine.DirectoryPath, "LightningRecaf-patches.jar"));
            classpath.Add(Path.Combine(CustomEngine.DirectoryPath, "LightningRecaf-branding.jar"));
            classpath.Add(CustomEngine.Jar);
            classpath.AddRange(new[] { "javafx-base", "javafx-graphics", "javafx-controls", "javafx-media" }
                .Select(a => CustomEngine.Dependency(a, jfxVersion)));

            var sb = new StringBuilder();
            sb.Append(Native.Quote(java.JavaExe));
            if (!string.IsNullOrEmpty(jvmArgs)) sb.Append(' ').Append(jvmArgs);
            sb.Append(" --enable-native-access=ALL-UNNAMED");
            sb.Append(' ').Append(Native.Quote("-DRECAF_DIR=" + Paths.CustomDataDir));
            if (Paths.IsPortable) {
                sb.Append(' ').Append(Native.Quote("-Djava.io.tmpdir=" + Path.Combine(Paths.LightningDir, "temp")));
                sb.Append(' ').Append(Native.Quote("-Djavafx.cachedir=" + Path.Combine(Paths.LightningDir, "cache", "javafx")));
            }
            sb.Append(" -cp ").Append(Native.Quote(string.Join(";", classpath)));
            sb.Append(' ').Append(MainClass);
            foreach (var a in recafArgs) sb.Append(' ').Append(Native.Quote(a));
            return sb.ToString();
        }

        public static void Start(JavaInstall java, string jfxVersion, string jvmArgs, List<string> recafArgs,
                                 IProgressSink ui, LaunchWorker worker)
        {
            if (Paths.IsPortable) Directory.CreateDirectory(Path.Combine(Paths.LightningDir, "temp"));
            string commandLine = BuildCommandLine(java, jfxVersion, jvmArgs, recafArgs);
            Log.Info("Command line: " + commandLine);

            // The console is hidden, so stdout/stderr go to a file; startup errors are shown from it.
            string outputPath;
            using (var output = OpenOutputLog(out outputPath))
            {
                Native.Launched proc = Native.StartWithOutput(commandLine, Paths.RecafDir, output.SafeFileHandle.DangerousGetHandle());
                Log.Info("Started java, pid " + proc.Pid);

                // Keep the splash up until Recaf shows a window (it takes a few seconds to boot),
                // or report the failure if the JVM dies first.
                var deadline = DateTime.UtcNow.AddSeconds(90);
                bool identitySet = false;
                while (DateTime.UtcNow < deadline)
                {
                    // Tag Recaf's window with our taskbar identity as early as possible (ideally before it is shown),
                    // so it groups with the pinned LightningRecaf.exe button and gets our jump list.
                    if (!identitySet)
                    {
                        IntPtr hwnd = Native.FindRecafWindow(proc.Pid, false);
                        if (hwnd != IntPtr.Zero)
                        {
                            ShellIntegration.ApplyWindowProperties(hwnd);
                            identitySet = true;
                        }
                    }
                    uint exitCode;
                    if (proc.WaitForExit(identitySet ? 250u : 60u, out exitCode))
                    {
                        Log.Info("Recaf exited early with code " + exitCode);
                        if (exitCode != 0)
                        {
                            output.Flush();
                            worker.FailureMessage = "Recaf exited during startup (code " + exitCode + ").\n\n" +
                                                    Tail(outputPath, 15) + "\n\nFull output: " + outputPath;
                        }
                        break;
                    }
                    if (Native.FindRecafWindow(proc.Pid, true) != IntPtr.Zero)
                    {
                        Log.Info("Recaf window is up");
                        break;
                    }
                }
                proc.Dispose();
            }
        }

        // The child inherits this handle, which only allows shared reads, so a second Recaf started while the
        // first is still running cannot truncate the first one's log; it gets its own file instead.
        static FileStream OpenOutputLog(out string path)
        {
            path = Paths.RecafOutputLog;
            try
            {
                return new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read);
            }
            catch (IOException)
            {
                path = Path.Combine(Paths.LauncherDir, "recaf-exe-output-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt");
                return new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read);
            }
        }

        static string Tail(string path, int lines)
        {
            try
            {
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var reader = new StreamReader(fs))
                {
                    var all = reader.ReadToEnd().Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries);
                    return string.Join("\n", all.Skip(Math.Max(0, all.Length - lines)));
                }
            }
            catch { return "(no output captured)"; }
        }
    }

    static class Native
    {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct STARTUPINFO
        {
            public int cb;
            public string lpReserved, lpDesktop, lpTitle;
            public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
            public short wShowWindow, cbReserved2;
            public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct PROCESS_INFORMATION
        {
            public IntPtr hProcess, hThread;
            public int dwProcessId, dwThreadId;
        }

        const int STARTF_USESTDHANDLES = 0x100;
        const uint CREATE_NO_WINDOW = 0x08000000;
        const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
        const uint HANDLE_FLAG_INHERIT = 1;
        const uint WAIT_OBJECT_0 = 0;

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool CreateProcess(string app, StringBuilder cmd, IntPtr pa, IntPtr ta, bool inherit, uint flags,
                                         IntPtr env, string cwd, ref STARTUPINFO si, out PROCESS_INFORMATION pi);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool SetHandleInformation(IntPtr h, uint mask, uint flags);

        [DllImport("kernel32.dll")]
        static extern uint WaitForSingleObject(IntPtr h, uint ms);

        [DllImport("kernel32.dll")]
        static extern bool GetExitCodeProcess(IntPtr h, out uint code);

        [DllImport("kernel32.dll")]
        static extern bool CloseHandle(IntPtr h);

        [DllImport("user32.dll")]
        static extern bool AllowSetForegroundWindow(int pid);

        delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

        [DllImport("user32.dll")]
        static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);

        [DllImport("user32.dll")]
        static extern uint GetWindowThreadProcessId(IntPtr hwnd, out int pid);

        [DllImport("user32.dll")]
        static extern bool IsWindowVisible(IntPtr hwnd);

        [DllImport("user32.dll")]
        static extern IntPtr GetWindow(IntPtr hwnd, uint cmd);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern int GetClassName(IntPtr hwnd, StringBuilder name, int max);

        public class Launched : IDisposable
        {
            public IntPtr Handle;
            public int Pid;

            public bool WaitForExit(uint ms, out uint exitCode)
            {
                exitCode = 0;
                if (WaitForSingleObject(Handle, ms) != WAIT_OBJECT_0) return false;
                GetExitCodeProcess(Handle, out exitCode);
                return true;
            }

            public void Dispose()
            {
                if (Handle != IntPtr.Zero) CloseHandle(Handle);
                Handle = IntPtr.Zero;
            }
        }

        public static Launched StartWithOutput(string commandLine, string cwd, IntPtr outputHandle)
        {
            SetHandleInformation(outputHandle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
            var si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(typeof(STARTUPINFO));
            si.dwFlags = STARTF_USESTDHANDLES;
            si.hStdOutput = outputHandle;
            si.hStdError = outputHandle;
            PROCESS_INFORMATION pi;
            IntPtr environment = IntPtr.Zero;
            bool ok; int error;
            try {
                if (Paths.IsPortable) environment = Marshal.StringToHGlobalUni(PortableEnvironment());
                ok = CreateProcess(null, new StringBuilder(commandLine), IntPtr.Zero, IntPtr.Zero, true,
                                   CREATE_NO_WINDOW | (Paths.IsPortable ? CREATE_UNICODE_ENVIRONMENT : 0),
                                   environment, cwd, ref si, out pi);
                error = Marshal.GetLastWin32Error();
            } finally {
                if (environment != IntPtr.Zero) Marshal.FreeHGlobal(environment);
                SetHandleInformation(outputHandle, HANDLE_FLAG_INHERIT, 0);
            }
            if (!ok) throw new System.ComponentModel.Win32Exception(error, "Could not start java.exe");
            CloseHandle(pi.hThread);
            // Recaf's window appears seconds later; let it take focus from our splash when it does.
            AllowSetForegroundWindow(pi.dwProcessId);
            return new Launched { Handle = pi.hProcess, Pid = pi.dwProcessId };
        }

        internal static string PortableEnvironment()
        {
            var variables = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (DictionaryEntry pair in Environment.GetEnvironmentVariables()) variables[(string)pair.Key] = (string)pair.Value;
            foreach (string name in new[] { "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "RECAF", "RECAF_DIR" }) variables.Remove(name);
            variables["JAVA_HOME"] = Paths.RuntimeDirectory;
            variables["TEMP"] = variables["TMP"] = Path.Combine(Paths.LightningDir, "temp");
            return string.Join("\0", variables.Select(pair => pair.Key + "=" + pair.Value)) + "\0\0";
        }

        // OpenJDK's Windows native launcher still uses the active ANSI code page
        // for its own runtime location. Reject lossy paths before its DLL loader fails.
        internal static bool JavaPathSupported(string path)
        {
            return Encoding.Default.GetString(Encoding.Default.GetBytes(path)) == path;
        }

        /// <summary>Finds the JavaFX stage window (class GlassWndClass-GlassWindowClass-*) of a process.</summary>
        public static IntPtr FindRecafWindow(int pid, bool visibleOnly)
        {
            IntPtr found = IntPtr.Zero;
            EnumWindows(delegate(IntPtr hwnd, IntPtr l)
            {
                int owner;
                GetWindowThreadProcessId(hwnd, out owner);
                if (owner != pid || GetWindow(hwnd, 4 /*GW_OWNER*/) != IntPtr.Zero) return true;
                if (visibleOnly && !IsWindowVisible(hwnd)) return true;
                // Only a JavaFX stage counts, not the toolkit helper or message windows.
                var cls = new StringBuilder(256);
                GetClassName(hwnd, cls, cls.Capacity);
                if (cls.ToString().StartsWith("GlassWndClass-GlassWindowClass", StringComparison.Ordinal))
                {
                    found = hwnd;
                    return false;
                }
                return true;
            }, IntPtr.Zero);
            return found;
        }

        // Quotes one argument following the MSVCRT/CommandLineToArgvW rules the JVM launcher uses.
        public static string Quote(string arg)
        {
            if (arg.Length > 0 && arg.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return arg;
            var sb = new StringBuilder("\"");
            int backslashes = 0;
            foreach (char c in arg)
            {
                if (c == '\\') { backslashes++; continue; }
                if (c == '"') { sb.Append('\\', backslashes * 2 + 1); sb.Append('"'); }
                else { sb.Append('\\', backslashes); sb.Append(c); }
                backslashes = 0;
            }
            sb.Append('\\', backslashes * 2);
            sb.Append('"');
            return sb.ToString();
        }

        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        public static void RoundCorners(IntPtr hwnd)
        {
            try
            {
                int round = 2; // DWMWCP_ROUND, Windows 11+
                DwmSetWindowAttribute(hwnd, 33 /*DWMWA_WINDOW_CORNER_PREFERENCE*/, ref round, sizeof(int));
            }
            catch { }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Splash window
    // ------------------------------------------------------------------------------------------------

    class SplashForm : Form, IProgressSink
    {
        static readonly Color Background = Color.FromArgb(12, 12, 12);
        static readonly Color Track = Color.FromArgb(48, 48, 48);
        static readonly Color AccentA = Color.FromArgb(110, 110, 110);
        static readonly Color AccentB = Color.FromArgb(235, 235, 235);
        static readonly Color TitleColor = Color.FromArgb(240, 240, 240);
        static readonly Color StatusColor = Color.FromArgb(160, 160, 160);

        readonly Image logo;
        readonly Font titleFont = new Font("Segoe UI Semibold", 15f);
        readonly Font statusFont = new Font("Segoe UI", 9f);
        readonly System.Windows.Forms.Timer animation = new System.Windows.Forms.Timer { Interval = 16 };
        readonly float scale;
        public string Title = "LightningRecaf";
        string status = "Starting...";
        double fraction = -1;
        float marquee;

        public SplashForm()
        {
            using (var g = Graphics.FromHwnd(IntPtr.Zero)) scale = g.DpiX / 96f;
            Text = Program.WindowTitle;
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(S(400), S(108));
            BackColor = Background;
            ShowInTaskbar = true;
            DoubleBuffered = true;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("logo.png"))
                if (s != null) logo = Image.FromStream(s);
            animation.Tick += delegate
            {
                marquee = (marquee + 0.012f) % 1.4f;
                Invalidate();
            };
            animation.Start();
            // Allow dragging the borderless window around.
            MouseDown += delegate(object sender, MouseEventArgs e)
            {
                if (e.Button != MouseButtons.Left) return;
                Capture = false;
                var msg = Message.Create(Handle, 0xA1 /*WM_NCLBUTTONDOWN*/, (IntPtr)2 /*HTCAPTION*/, IntPtr.Zero);
                WndProc(ref msg);
            };
        }

        int S(int v) { return (int)Math.Round(v * scale); }

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ClassStyle |= 0x20000; // CS_DROPSHADOW
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Native.RoundCorners(Handle);
        }

        public void SetStatus(string text, double frac)
        {
            if (!IsHandleCreated) return;
            try
            {
                BeginInvoke((MethodInvoker)delegate
                {
                    status = text;
                    fraction = frac;
                    Invalidate();
                });
            }
            catch (InvalidOperationException) { }
        }

        public Action BeforeClose;

        public void CloseFromWorker()
        {
            try
            {
                BeginInvoke((MethodInvoker)delegate
                {
                    if (BeforeClose != null) BeforeClose();
                    Close();
                });
            }
            catch (InvalidOperationException) { }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;

            int pad = S(22);
            int logoSize = S(60);
            if (logo != null) g.DrawImage(logo, pad, (ClientSize.Height - logoSize) / 2 - S(4), logoSize, logoSize);

            int textX = pad + logoSize + S(16);
            int textW = ClientSize.Width - textX - pad;
            TextRenderer.DrawText(g, Title, titleFont, new Point(textX - S(2), S(20)), TitleColor);
            TextRenderer.DrawText(g, status, statusFont, new Rectangle(textX, S(52), textW, S(20)), StatusColor,
                TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix | TextFormatFlags.Left);

            var bar = new Rectangle(textX, S(80), textW, S(4));
            using (var track = new SolidBrush(Track)) FillRounded(g, track, bar);
            RectangleF fill;
            if (fraction >= 0)
            {
                fill = new RectangleF(bar.X, bar.Y, (float)(bar.Width * Math.Min(1, fraction)), bar.Height);
            }
            else
            {
                float w = bar.Width * 0.35f;
                float x = bar.X - w + (bar.Width + w) * (marquee / 1.4f) * 1.0f;
                float left = Math.Max(bar.X, x), right = Math.Min(bar.Right, x + w);
                fill = new RectangleF(left, bar.Y, Math.Max(0, right - left), bar.Height);
            }
            if (fill.Width >= 1)
            {
                using (var brush = new LinearGradientBrush(new RectangleF(bar.X - 1, bar.Y, bar.Width + 2, bar.Height), AccentA, AccentB, 0f))
                    FillRounded(g, brush, fill);
            }
        }

        static void FillRounded(Graphics g, Brush brush, RectangleF r)
        {
            float d = Math.Min(r.Height, r.Width);
            if (d < 1) return;
            using (var path = new GraphicsPath())
            {
                path.AddArc(r.X, r.Y, d, d, 90, 180);
                path.AddArc(r.Right - d, r.Y, d, d, 270, 180);
                path.CloseFigure();
                g.FillPath(brush, path);
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                animation.Dispose();
                titleFont.Dispose();
                statusFont.Dispose();
                if (logo != null) logo.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
