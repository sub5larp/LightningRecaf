using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;

[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8")]
[assembly: AssemblyTitle("LightningRecaf")]
[assembly: AssemblyProduct("LightningRecaf")]
[assembly: AssemblyDescription("LightningRecaf standalone Windows application")]
[assembly: AssemblyVersion("2.3.5.0")]
[assembly: AssemblyFileVersion("2.3.5.0")]

namespace LightningRecaf.Standalone
{
    internal static class Bootstrap
    {
        static Stream Resource(string name) {
            var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name);
            if (stream == null) throw new InvalidDataException("Application package is missing. Download the EXE again.");
            return stream;
        }
        static string TextResource(string name) {
            using (var reader = new StreamReader(Resource(name), Encoding.UTF8)) return reader.ReadToEnd().Trim();
        }
        static string Digest(Stream stream) {
            using (var sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
        }
        internal static string SafePath(string root, string relative) {
            if (string.IsNullOrEmpty(relative) || relative.Contains(":") || relative.Contains("\\") || Path.IsPathRooted(relative))
                throw new InvalidDataException("Invalid application package path.");
            foreach (string part in relative.Split('/'))
                if (part.Length == 0 || part == "." || part == ".." || part.EndsWith(".") || part.EndsWith(" "))
                    throw new InvalidDataException("Invalid application package path.");
            string boundary = Path.GetFullPath(root).TrimEnd('\\') + "\\";
            string path = Path.GetFullPath(Path.Combine(boundary, relative.Replace('/', '\\')));
            if (!path.StartsWith(boundary, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Invalid application package path.");
            return path;
        }
        static Dictionary<string, string> Manifest() {
            var entries = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (string line in TextResource("package.manifest").Split('\n')) {
                string row = line.TrimEnd('\r');
                if (row.Length < 67 || row.Substring(64, 2) != "  ") throw new InvalidDataException("Invalid package manifest.");
                entries.Add(row.Substring(66), row.Substring(0, 64));
            }
            return entries;
        }
        static void RejectLink(string path) {
            if ((File.Exists(path) || Directory.Exists(path)) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("The application cache must not contain redirected folders or files: " + path);
        }
        static bool Valid(string root, Dictionary<string, string> manifest) {
            if (!Directory.Exists(root)) return false;
            RejectLink(root);
            foreach (var item in manifest) {
                string path = SafePath(root, item.Key);
                for (string dir = Path.GetDirectoryName(path); dir.Length >= root.Length; dir = Path.GetDirectoryName(dir)) RejectLink(dir);
                RejectLink(path);
                if (!File.Exists(path)) return false;
                using (var input = File.OpenRead(path)) if (Digest(input) != item.Value) return false;
            }
            return true;
        }
        internal static string EnsurePackage(string destination, bool cached, Action<int> progress) {
            string root = Path.GetFullPath(destination).TrimEnd('\\');
            string parent = Path.GetDirectoryName(root);
            if (string.IsNullOrEmpty(parent)) throw new IOException("Choose a folder below the drive root.");
            Directory.CreateDirectory(parent);
            RejectLink(parent);
            if (!cached && (Directory.Exists(root) || File.Exists(root))) throw new IOException("Extraction destination already exists. Choose a new folder.");
            string lockPath = root + ".lock";
            RejectLink(lockPath);
            FileStream gate = null;
            var timer = Stopwatch.StartNew();
            while (gate == null) {
                try { gate = new FileStream(lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
                catch (IOException) { if (timer.Elapsed.TotalMinutes >= 3) throw new IOException("Another LightningRecaf copy is still preparing the application. Try again shortly."); Thread.Sleep(150); }
            }
            using (gate) {
                var manifest = Manifest();
                if (cached && Valid(root, manifest)) {
                    File.WriteAllText(Path.Combine(root, "LightningRecaf.standalone"), "standalone");
                    return root;
                }
                if (!cached && (Directory.Exists(root) || File.Exists(root))) throw new IOException("Extraction destination already exists. Choose a new folder.");
                string staging = root + ".partial-" + Guid.NewGuid().ToString("N");
                Directory.CreateDirectory(staging);
                try {
                    using (var payload = Resource("package.zip")) {
                        if (Digest(payload) != TextResource("package.sha256")) throw new InvalidDataException("Application download is damaged. Download the EXE again.");
                        payload.Position = 0;
                        using (var zip = new ZipArchive(payload, ZipArchiveMode.Read)) {
                            var found = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                            int done = 0;
                            foreach (var entry in zip.Entries) {
                                string expected;
                                if (!manifest.TryGetValue(entry.FullName, out expected) || !found.Add(entry.FullName)) throw new InvalidDataException("Unexpected application package entry.");
                                string target = SafePath(staging, entry.FullName);
                                Directory.CreateDirectory(Path.GetDirectoryName(target));
                                using (var input = entry.Open()) using (var output = new FileStream(target, FileMode.CreateNew)) input.CopyTo(output);
                                using (var input = File.OpenRead(target)) if (Digest(input) != expected) throw new InvalidDataException("Damaged application file: " + entry.FullName);
                                progress(++done * 100 / manifest.Count);
                            }
                            if (found.Count != manifest.Count) throw new InvalidDataException("Incomplete application package.");
                        }
                    }
                    if (cached) File.WriteAllText(Path.Combine(staging, "LightningRecaf.standalone"), "standalone");
                    if (Directory.Exists(root)) {
                        RejectLink(root);
                        // Keep a damaged cache intact in case the user stored additional files in it.
                        Directory.Move(root, root + ".previous-" + Guid.NewGuid().ToString("N"));
                    }
                    Directory.Move(staging, root);
                    return root;
                } finally {
                    if (Directory.Exists(staging)) Directory.Delete(staging, true);
                }
            }
        }
        internal static string Quote(string value) {
            var result = new StringBuilder("\"");
            int slashes = 0;
            foreach (char c in value) {
                if (c == '\\') { slashes++; continue; }
                result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
                result.Append(c);
                slashes = 0;
            }
            return result.Append('\\', slashes * 2).Append('"').ToString();
        }
        internal static ProcessStartInfo LaunchInfo(string root, string[] args) {
            var quoted = new List<string>();
            foreach (string arg in args) quoted.Add(Quote(arg));
            return new ProcessStartInfo(Path.Combine(root, "LightningRecaf.exe"), string.Join(" ", quoted.ToArray())) {
                UseShellExecute = false, WorkingDirectory = Environment.CurrentDirectory
            };
        }
        [STAThread]
        static int Main(string[] args) {
            AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
            AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
            bool extract = args.Length > 0 && args[0] == "--extract";
            try {
                if (!Environment.Is64BitOperatingSystem) throw new PlatformNotSupportedException("LightningRecaf requires 64-bit Windows.");
                if (extract) {
                    if (args.Length != 2) throw new ArgumentException("Usage: LightningRecaf.exe --extract <new-folder>");
                    EnsurePackage(args[1], false, delegate(int percent) { });
                    return 0;
                }
                string cache = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LightningRecaf", "app", TextResource("package.sha256").Substring(0, 20));
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Exception failure = null;
                using (var form = new Form()) {
                    form.Text = "LightningRecaf";
                    form.ClientSize = new Size(390, 104);
                    form.FormBorderStyle = FormBorderStyle.FixedDialog;
                    form.StartPosition = FormStartPosition.CenterScreen;
                    form.ControlBox = false;
                    form.BackColor = Color.FromArgb(18, 18, 18);
                    form.ForeColor = Color.White;
                    form.Font = new Font("Segoe UI", 10);
                    using (var icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath)) form.Icon = (Icon)icon.Clone();
                    var label = new Label { Text = "Preparing LightningRecaf…", AutoSize = true, Left = 22, Top = 22 };
                    var bar = new ProgressBar { Left = 22, Top = 58, Width = 346, Height = 18 };
                    form.Controls.Add(label); form.Controls.Add(bar);
                    form.Shown += delegate {
                        new Thread(delegate() {
                            try { EnsurePackage(cache, true, delegate(int percent) { form.BeginInvoke((Action)delegate { bar.Value = percent; }); }); }
                            catch (Exception ex) { failure = ex; }
                            finally { form.BeginInvoke((Action)delegate { form.Close(); }); }
                        }) { IsBackground = true }.Start();
                    };
                    Application.Run(form);
                }
                if (failure != null) throw failure;
                using (var child = Process.Start(LaunchInfo(cache, args))) {
                    child.WaitForExit();
                    return child.ExitCode;
                }
            } catch (Exception ex) {
                if (extract) Console.Error.WriteLine(ex.Message);
                else MessageBox.Show(ex.Message, "LightningRecaf", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }
    }
}
