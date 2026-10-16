using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace RecafExe
{
    static class StartupCache
    {
        internal static string Arguments(JavaInstall java, IList<string> classpath, string customArguments)
        {
            if (java.Major < 25 || !string.IsNullOrWhiteSpace(customArguments)) return "";
            try {
                var identity = new StringBuilder(java.Home).Append('\n');
                string release = Path.Combine(java.Home, "release");
                if (File.Exists(release)) identity.Append(File.ReadAllText(release));
                foreach (string path in classpath) {
                    var file = new FileInfo(path);
                    identity.Append('\n').Append(file.FullName).Append('|').Append(file.Length).Append('|').Append(file.LastWriteTimeUtc.Ticks);
                }
                string key;
                using (var sha = SHA256.Create()) key = BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(identity.ToString()))).Replace("-", "");
                string directory = Path.Combine(Paths.LightningDir, "cache", "startup", key);
                Directory.CreateDirectory(directory);
                string cache = Directory.GetFiles(directory, "*.aot").OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault();
                // Auto mode falls back to normal startup when the JDK cannot use a cache.
                if (cache != null) return " -XX:AOTMode=auto " + Native.Quote("-XX:AOTCache=" + cache);
                string output = Path.Combine(directory, Guid.NewGuid().ToString("N") + ".aot");
                return " -XX:AOTMode=auto -Dlightning.aot.record=true " + Native.Quote("-XX:AOTCacheOutput=" + output);
            } catch (Exception error) {
                Log.Info("Startup cache unavailable: " + error.Message);
                return "";
            }
        }
    }
}
