package com.dmtavt.fragpipe.tools.comet;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class CometPinUpdateDecoyLabel {
    private static boolean isDebug = true;
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            exit("Usage: CometPinUpdateDecoyLabel <regExp-for-decoy-prot> <path-to-comet-pin>");
        }
        final Path pathIn = Paths.get(args[1]);
        if (!Files.exists(pathIn)) {
            exit("File not exists: " + pathIn);
        }
        if (Files.isDirectory(pathIn)) {
            exit("Directory given instead of file: " + pathIn);
        }
        final Pattern re = Pattern.compile(args[0]);

        List<String> lines = Files.readAllLines(pathIn);
        if (lines.isEmpty())
            exit("input Pin file didn't even have a header line");
        final String header = lines.get(0);
        // find where Proteins column is
        final String[] split = header.split("\t");
        final int indexOfProts = Arrays.asList(split).indexOf("Proteins");
        if (indexOfProts < 0)
            exit("Could not find 'Proteins' entry in the header of the given pin file");


        final String fnTmp = pathIn.getFileName() + ".fixed-label";
        final Path pathOutTmp = pathIn.getParent().resolve(fnTmp);
        try (BufferedWriter f = Files.newBufferedWriter(pathOutTmp, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
            f.write(header);
            for (int lineNum = 1; lineNum <lines.size(); lineNum++) {
                final String line = lines.get(lineNum);
                int cnt = indexOfProts;
                int offset = 0;
                while (cnt > 0) {
                    offset = line.indexOf('\t', offset);
                    cnt -= 1;
                    if (offset == 0)
                }
            }
        }
    }

    public static void exit(String msg) {
        System.err.println(msg);
        System.exit(1);
    }
}
