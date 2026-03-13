package org.schabi.newpipe.util;

import org.schabi.newpipe.streams.io.SharpInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.schabi.newpipe.streams.io.StoredFileHelper;

/**
 * Created by Christian Schabesberger on 28.01.18.
 * Copyright 2018 Christian Schabesberger <chris.schabesberger@mailbox.org>
 * ZipHelper.java is part of NewPipe
 * <p>
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

public final class ZipHelper {
    private ZipHelper() { }

    private static final int BUFFER_SIZE = 2048;

    /**
     * This function helps to create zip files.
     * Caution this will override the original file.
     *
     * @param outZip The ZipOutputStream where the data should be stored in
     * @param file   The path of the file that should be added to zip.
     * @param name   The path of the file inside the zip.
     * @throws Exception
     */
    public static void addFileToZip(final ZipOutputStream outZip, final String file,
                                    final String name) throws Exception {
        final byte[] data = new byte[BUFFER_SIZE];
        try (FileInputStream fi = new FileInputStream(file);
             BufferedInputStream inputStream = new BufferedInputStream(fi, BUFFER_SIZE)) {
            final ZipEntry entry = new ZipEntry(name);
            outZip.putNextEntry(entry);
            int count;
            while ((count = inputStream.read(data, 0, BUFFER_SIZE)) != -1) {
                outZip.write(data, 0, count);
            }
        }
    }

    /**
     * Adds a UTF-8 text file to a zip archive.
     *
     * @param outZip   The ZipOutputStream where the data should be stored in
     * @param name     The path of the file inside the zip.
     * @param contents The UTF-8 content to store.
     * @throws IOException if the entry cannot be written
     */
    public static void addStringToZip(final ZipOutputStream outZip,
                                      final String name,
                                      final String contents) throws IOException {
        final ZipEntry entry = new ZipEntry(name);
        outZip.putNextEntry(entry);
        outZip.write(contents.getBytes(StandardCharsets.UTF_8));
        outZip.closeEntry();
    }

    /**
     * This will extract data from ZipInputStream.
     * Caution this will override the original file.
     *
     * @param zipFile The zip file
     * @param file The path of the file on the disk where the data should be extracted to.
     * @param name The path of the file inside the zip.
     * @return will return true if the file was found within the zip file
     * @throws Exception
     */
    public static boolean extractFileFromZip(final StoredFileHelper zipFile, final String file,
                                             final String name) throws Exception {
        try (ZipInputStream inZip = new ZipInputStream(new BufferedInputStream(
                new SharpInputStream(zipFile.getStream())))) {
            final byte[] data = new byte[BUFFER_SIZE];
            boolean found = false;
            ZipEntry ze;

            while ((ze = inZip.getNextEntry()) != null) {
                if (ze.getName().equals(name)) {
                    found = true;
                    // delete old file first
                    final File oldFile = new File(file);
                    if (oldFile.exists()) {
                        if (!oldFile.delete()) {
                            throw new Exception("Could not delete " + file);
                        }
                    }

                    try (FileOutputStream outFile = new FileOutputStream(file)) {
                        int count = 0;
                        while ((count = inZip.read(data)) != -1) {
                            outFile.write(data, 0, count);
                        }
                    }

                    inZip.closeEntry();
                }
            }
            return found;
        }
    }

    /**
     * Reads a UTF-8 text file from a zip archive.
     *
     * @param zipFile The zip file
     * @param name The path of the file inside the zip.
     * @return the text content if found, otherwise {@code null}
     * @throws Exception if reading the zip fails
     */
    public static String readTextFileFromZip(final StoredFileHelper zipFile,
                                             final String name) throws Exception {
        try (ZipInputStream inZip = new ZipInputStream(new BufferedInputStream(
                new SharpInputStream(zipFile.getStream())))) {
            final byte[] data = new byte[BUFFER_SIZE];
            ZipEntry ze;

            while ((ze = inZip.getNextEntry()) != null) {
                if (ze.getName().equals(name)) {
                    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        int count;
                        while ((count = inZip.read(data)) != -1) {
                            out.write(data, 0, count);
                        }
                        inZip.closeEntry();
                        return out.toString(StandardCharsets.UTF_8.name());
                    }
                }
            }
        }
        return null;
    }

    public static boolean isValidZipFile(final StoredFileHelper file) {
        try (ZipInputStream ignored = new ZipInputStream(new BufferedInputStream(
                new SharpInputStream(file.getStream())))) {
            return true;
        } catch (final IOException ioe) {
            return false;
        }
    }
}
