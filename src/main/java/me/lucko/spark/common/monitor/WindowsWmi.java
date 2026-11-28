/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.monitor;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility for reading from the WMI (Windows Management Instrumentation) service via PowerShell on Windows systems.
 * wmic, used before, is gone from Windows 11 24H2 and later.
 */
public enum WindowsWmi {

    /**
     * Get the processor name.
     */
    PROCESSOR_NAME("(Get-CimInstance Win32_Processor).Name"),

    /**
     * Get the operating system caption.
     */
    OPERATING_SYSTEM_CAPTION("(Get-CimInstance Win32_OperatingSystem).Caption"),

    /**
     * Get the operating system version.
     */
    OPERATING_SYSTEM_VERSION("(Get-CimInstance Win32_OperatingSystem).Version");

    private static final boolean SUPPORTED = System.getProperty("os.name").startsWith("Windows");

    private final String command;
    // these values don't change while running, and starting PowerShell takes a while
    private volatile List<String> cached;

    WindowsWmi(String command) {
        this.command = command;
    }

    public @NonNull List<String> read() {
        List<String> lines = this.cached;
        if (lines == null) {
            lines = SUPPORTED ? query() : Collections.emptyList();
            this.cached = lines;
        }
        return lines;
    }

    private List<String> query() {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                // captions are localized, so don't leave the encoding to the console code page
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8; " + this.command
        ).redirectErrorStream(true);

        try {
            Process process = builder.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader buf = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = buf.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lines.add(line.trim());
                    }
                }
            }
            // on failure the output is PowerShell's error message, not a value
            return process.waitFor() == 0 ? Collections.unmodifiableList(lines) : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
