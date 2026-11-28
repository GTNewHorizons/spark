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

import me.lucko.spark.common.monitor.cpu.CpuInfo;
import me.lucko.spark.common.monitor.os.OperatingSystemInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
public class WindowsWmiTest {

    @Test
    public void testCpuModel() {
        String model = CpuInfo.queryCpuModel();
        System.out.println(model);
        assertFalse(model.isEmpty());
    }

    @Test
    public void testOperatingSystem() {
        OperatingSystemInfo info = OperatingSystemInfo.poll();
        System.out.println(info.name() + " " + info.version());
        // the java fallbacks are "Windows 11" and "10.0"; WMI gives the edition and build
        assertTrue(info.name().startsWith("Windows ") && !info.name().equals(System.getProperty("os.name")), info.name());
        assertTrue(info.version().matches("\\d+\\.\\d+\\.\\d+"), info.version());
    }
}
