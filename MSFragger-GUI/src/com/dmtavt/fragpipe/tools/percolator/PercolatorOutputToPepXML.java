/*
 * This file is part of FragPipe.
 *
 * FragPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FragPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with FragPipe.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.dmtavt.fragpipe.tools.percolator;

import org.jooq.lambda.function.Consumer3;

import javax.xml.stream.XMLStreamException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PercolatorOutputToPepXML {

    private static final Pattern pattern = Pattern.compile("(.+spectrum=\".+\\.)([0-9]+)\\.([0-9]+)(\\.[0-9]+\".+)");
    private static final Pattern reSpecRankPinComet = Pattern.compile("([^\\\\/]+)_\\d+_(\\d+)$");
    private static final Pattern reSpecRankPinMsFragger = Pattern.compile("([^\\\\/]+)_\\d+_(\\d+)$");
    private static final Pattern reSpecRankPercolator = Pattern.compile("([^\\\\/]+)_\\d+_(\\d+)$");

    public static void main(final String[] args) {
        Locale.setDefault(Locale.US);
        if (args.length == 0)
            percolatorToPepXML(
                    Paths.get("F:\\dev\\msfragger\\msfraggerdia_old\\20190206_LUM1_CPBA_EASY04_060_30_SA_90mingrad_80B_DIA_400_1000_8mzol_15k_20IIT_4e5agc_1633-01_01.pin"),
                    "F:\\dev\\msfragger\\msfraggerdia_old\\20190206_LUM1_CPBA_EASY04_060_30_SA_90mingrad_80B_DIA_400_1000_8mzol_15k_20IIT_4e5agc_1633-01_01",
                    Paths.get("F:\\dev\\msfragger\\msfraggerdia_old\\20190206_LUM1_CPBA_EASY04_060_30_SA_90mingrad_80B_DIA_400_1000_8mzol_15k_20IIT_4e5agc_1633-01_01_percolator_target_psms.tsv"),
                    Paths.get("F:\\dev\\msfragger\\msfraggerdia_old\\20190206_LUM1_CPBA_EASY04_060_30_SA_90mingrad_80B_DIA_400_1000_8mzol_15k_20IIT_4e5agc_1633-01_01_percolator_decoy_psms.tsv"),
                    Paths.get("F:\\dev\\msfragger\\msfraggerdia_old\\interact-20190206_LUM1_CPBA_EASY04_060_30_SA_90mingrad_80B_DIA_400_1000_8mzol_15k_20IIT_4e5agc_1633-01_01"),
                    "DIA",
                    0);
        else
            percolatorToPepXML(Paths.get(args[0]), args[1], Paths.get(args[2]), Paths.get(args[3]), Paths.get(args[4]), args[5], Double.parseDouble(args[6]));
    }

    private static String getSpectrum(final String line) {
        String spectrum = null;
        for (final String e : line.split("\\s"))
            if (e.startsWith("spectrum=")) {
                spectrum = e.substring("spectrum=\"".length(), e.length() - 1);
                break;
            }
        return spectrum.substring(0, spectrum.lastIndexOf("."));
    }

    private static String paddingZeros(final String line) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.matches()) {
            if (matcher.group(2).contentEquals(matcher.group(3))) {
                String scanNum = matcher.group(2);
                if (scanNum.length() < 5) {
                    StringBuilder sb = new StringBuilder(5);
                    for (int i = 0; i < 5 - scanNum.length(); ++i) {
                        sb.append("0");
                    }
                    sb.append(scanNum);
                    return matcher.group(1) + sb + "." + sb + matcher.group(4);
                } else {
                    return line;
                }
            } else {
                throw new RuntimeException("Cannot parse spectrum ID from  " + line);
            }
        } else {
            throw new RuntimeException("Cannot parse line " + line);
        }
    }

    private static class Spectrum_rank {
        final String spectrum;
        final int rank;

        Spectrum_rank(String spectrum, int rank) {
            this.spectrum = spectrum;
            this.rank = rank;
        }
    }

    /**
     * Example to be parsed: Human-Protein-Training_Trypsin.12198.12198.4_1
     */
    private static Spectrum_rank get_spectrum_rank_msfragger(final String s){
        try {
            final int lastIndexOfDot = s.lastIndexOf(".");
            final String charge_rank = s.substring(lastIndexOfDot);
            final int rank = Integer.parseInt(charge_rank.split("_")[1]);
            return new Spectrum_rank(s.substring(0, lastIndexOfDot), rank);
        } catch (StringIndexOutOfBoundsException e) {
            exit("\"Unexpected input string for parsing MsFragger's SpectrumRank from SpecId: " + s);
        }
        throw new IllegalStateException();
    }

    /**
     * Example to be parsed: C:\Human-Protein-Training_Trypsin_821_3_2
     */
    private static Spectrum_rank get_spectrum_rank_comet(String s) {
        Matcher m = reSpecRankPinComet.matcher(s);
        if (!m.find())
            throw new IllegalStateException("Unexpected input string for parsing Comet's SpectrumRank from SpecId: " + s);
        return new Spectrum_rank(m.group(1), Integer.parseInt(m.group(2)));
    }

    private static List<StoxParserPepxml.NameValue> parseParamsFromPepxml(Path pepxmlPath) throws XMLStreamException, IOException {
        List<StoxParserPepxml.NameValue> params = StoxParserPepxml.parseSearchSummary(pepxmlPath);
        return params;
    }

    private static int findMaxRank(List<StoxParserPepxml.NameValue> params) {
        for (StoxParserPepxml.NameValue kv : params) {
            if ("output_report_topN".equals(kv.k)           // MsFragger
                    || "num_output_lines".equals(kv.k)) {   // Comet
                return Integer.parseInt(kv.v);
            }
        }
        throw new IllegalStateException("Did not find `output_report_topN` or `num_output_lines` search engine parameters");
    }

    private static StringBuilder handle_search_hit(final List<String> searchHit, final NttNmc nttNmc, final PepScore pepScore, final int oldRank, final int newRank) {
        if (nttNmc == null || pepScore == null) {
            return new StringBuilder();
        }

        final StringBuilder sb = new StringBuilder();
        double calc_neutral_pep_mass = Double.NaN;
        double massdiff = Double.NaN;
        int isomassd = 0;
        final Iterator<String> iterator = searchHit.iterator();
        final String search_hit_line = iterator.next();
        for (final String e : search_hit_line.split("\\s")) { // fixme: the code assumes that all attributes are in one line, which makes it not robust
            if (e.startsWith("massdiff="))
                massdiff = Double.parseDouble(e.substring("massdiff=\"".length(), e.length() - 1));
            if (e.startsWith("calc_neutral_pep_mass="))
                calc_neutral_pep_mass = Double.parseDouble(e.substring("calc_neutral_pep_mass=\"".length(), e.length() - 1));
        }
        double gap = Double.MAX_VALUE;
        for (int isotope = -6; isotope < 7; ++isotope) {
            if (Math.abs(massdiff - isotope * 1.0033548378) < gap) {
                gap = Math.abs(massdiff - isotope * 1.0033548378);
                isomassd = isotope;
            }
        }
        if (gap > 0.1) { // It may be from an open search.
            isomassd = 0;
        }
        sb.append(oldRank == newRank ? search_hit_line : search_hit_line.replace("hit_rank=\"" + oldRank + "\"", "hit_rank=\"" + newRank + "\"")).append("\n");
        String line;
        while (!(line = iterator.next()).trim().contentEquals("</search_hit>"))
            sb.append(line).append("\n");
        {
            sb.append(
                    String.format(
                            "<analysis_result analysis=\"peptideprophet\">\n" +
                                    "<peptideprophet_result probability=\"%f\" all_ntt_prob=\"(%f,%f,%f)\">\n" +
                                    "<search_score_summary>\n" +
                                    "<parameter name=\"fval\" value=\"%f\"/>\n" +
                                    "<parameter name=\"ntt\" value=\"%d\"/>\n" +
                                    "<parameter name=\"nmc\" value=\"%d\"/>\n" +
                                    "<parameter name=\"massd\" value=\"%f\"/>\n" +
                                    "<parameter name=\"isomassd\" value=\"%d\"/>\n" +
                                    "</search_score_summary>\n" +
                                    "</peptideprophet_result>\n" +
                                    "</analysis_result>\n",
                            1 - pepScore.pep, 1 - pepScore.pep, 1 - pepScore.pep, 1 - pepScore.pep,
                            pepScore.score, nttNmc.ntt, nttNmc.nmc, (massdiff - isomassd * 1.0033548378) * 1e6 / calc_neutral_pep_mass, isomassd
                    ));
        }
        sb.append("</search_hit>\n");
        return sb;
    }

    private static String handle_spectrum_query(final List<String> sq, final Map<String, NttNmc[]> pinSpectrumRankNttNmc, final Map<String, PepScore[]> pinSpectrumRankPepScore, final boolean is_DIA, final int DIA_rank) {
        final List<List<String>> search_hits = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        String spectrum;
        final Iterator<String> iterator = sq.iterator();
        for (String line; iterator.hasNext(); ) {
            line = iterator.next().trim();
            spectrum = getSpectrum(line);

            final PepScore[] pepScoreArray = pinSpectrumRankPepScore.get(spectrum);
            if (pepScoreArray == null) {
                return "";
            }

            final NttNmc[] nttNmcArray = pinSpectrumRankNttNmc.get(spectrum);
            if (nttNmcArray == null) {
                return "";
            }

            if (is_DIA && (nttNmcArray[DIA_rank - 1] == null || pepScoreArray[DIA_rank - 1] == null)) {
                return "";
            }

            sb.append(paddingZeros(line)).append('\n');
            while (iterator.hasNext()) { // fixme: the code assumes that there are always <search_hit, massdiff=, and calc_neutral_pep_mass=, which makes it not robust
                line = iterator.next().trim();
                if (line.startsWith("<search_result>"))
                    sb.append(line).append('\n');
                else if (line.trim().startsWith("<search_hit ")) {
                    final ArrayList<String> search_hit = new ArrayList<>();
                    search_hit.add(line);
                    do {
                        line = iterator.next();
                        search_hit.add(line);
                    } while (!line.contentEquals("</search_hit>"));
                    search_hits.add(search_hit);
                } else if (line.trim().startsWith("</search_result>")) {
                    if (is_DIA) // FixMe: it does not reorder the hits according to ranks updated by Percolator.
                        sb.append(handle_search_hit(search_hits.get(0), nttNmcArray[DIA_rank - 1], pepScoreArray[DIA_rank - 1], 1, 1));
                    else {
                        // write the search_hits ordered by Percolator
                        final TreeMap<Double, Integer> scoreOldRankMinusOne = new TreeMap<>(Collections.reverseOrder());
                        for (int oldRankMinusOne = 0; oldRankMinusOne < pepScoreArray.length; ++oldRankMinusOne) {
                            final PepScore pepScore = pepScoreArray[oldRankMinusOne];
                            if (pepScore == null) {
                                continue;
                            }
                            scoreOldRankMinusOne.put(pepScore.score, oldRankMinusOne);
                        }
                        int newRank = 0;
                        for (final Map.Entry<Double, Integer> entry : scoreOldRankMinusOne.entrySet()) {
                            final int oldRankMinusOne = entry.getValue();
                            sb.append(handle_search_hit(search_hits.get(oldRankMinusOne), nttNmcArray[oldRankMinusOne], pepScoreArray[oldRankMinusOne], oldRankMinusOne + 1, ++newRank));
                        }
                    }
                    sb.append(line).append('\n');
                } else if (line.trim().startsWith("</spectrum_query>"))
                    sb.append(line).append('\n');
                else
                    throw new IllegalStateException(line);
            }
        }
        return sb.toString();
    }

    private static void exit(String message, Throwable ex) {
        if (message != null)
            System.err.println(message);
        if (ex != null)
            ex.printStackTrace();
        System.exit(1);
    }
    private static void exit(String message) {
        exit(message, null);
    }

    public enum SearchEngine {Unknown, MsFragger, Comet}

    public static void percolatorToPepXML(final Path pin, final String basename, final Path percolatorTargetPsms,
                                          final Path percolatorDecoyPsms, final Path outBasename, final String DIA_DDA, final double minProb) {
        // get max rank from pin
        final boolean is_DIA = DIA_DDA.equals("DIA");
        final Path pathPepxml = is_DIA
                ? Paths.get(basename + "_rank1.pepXML")
                : Paths.get(basename + ".pepXML");

        List<StoxParserPepxml.NameValue> nameValues = null;
        try {
            nameValues = parseParamsFromPepxml(pathPepxml);
        } catch (XMLStreamException | IOException e) {
            exit("Couldn't parse search parameters from pepXML");
        }

        final int max_rank = findMaxRank(nameValues);
        if (max_rank < 1) {
            exit("Couldn't find max reported peptide rank in pepxml search engine parameters");
        }

        final SearchEngine se = findSearchEngine(nameValues);

        final Map<String, NttNmc[]> pinSpectrumRankNttNmc = new HashMap<>();
        parsePinAsRankNttNmc(pin, max_rank, pinSpectrumRankNttNmc, se);

        final Map<String, PepScore[]> pinSpectrumRankPepScore = new HashMap<>();
        parsePercolatorTsvAsRankPepScore(percolatorTargetPsms, percolatorDecoyPsms, minProb, max_rank, pinSpectrumRankPepScore);

        for (int rank = 1; rank <= (is_DIA ? max_rank : 1); ++rank) {
            final Path output_rank = is_DIA
                    ? Paths.get(outBasename + "_rank" + rank + ".pep.xml")
                    : Paths.get(outBasename + ".pep.xml");
            final Path pepxml_rank = is_DIA
                    ? Paths.get(basename + "_rank" + rank + ".pepXML")
                    : Paths.get(basename + ".pepXML");
            // fixme: cannot parse XML line-by-line because line break is allowed everywhere, including within an attribute, in a XML. Need to parse it using JDOM or JAXB
            try (final BufferedReader brpepxml = Files.newBufferedReader(pepxml_rank);
                 final BufferedWriter out = Files.newBufferedWriter(output_rank)) {
                String line;
                while ((line = brpepxml.readLine()) != null) {
                    out.write(line + "\n");
                    if (line.trim().startsWith("<msms_pipeline_analysis ")) {
                        final String now = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").format(LocalDateTime.now());
                        final String tmp = String.format(
                                "<analysis_summary analysis=\"Percolator\" time=\"%s\">\n" +
                                "<peptideprophet_summary min_prob=\"%.2f\">\n" +
                                "<inputfile name=\"%s\"/>\n" +
                                "</peptideprophet_summary>\n" +
                                "</analysis_summary>\n" +
                                "<analysis_summary analysis=\"database_refresh\" time=\"%s\"/>\n" +
                                "<analysis_summary analysis=\"interact\" time=\"%s\">\n" +
                                "<interact_summary filename=\"%s\" directory=\"\">\n" +
                                "<inputfile name=\"%s\"/>\n" +
                                "</interact_summary>\n" +
                                "</analysis_summary>\n" +
                                "<dataset_derivation generation_no=\"0\"/>\n",
                                now, minProb, pepxml_rank.toAbsolutePath(), now, now, output_rank.toAbsolutePath(), pepxml_rank.toAbsolutePath());
                        out.write(tmp);
                    }
                    if (line.trim().equals("</search_summary>"))
                        break;
                }

                while ((line = brpepxml.readLine()) != null) {
                    if (line.trim().startsWith("<spectrum_query")) {
                        final List<String> sq = new ArrayList<>();
                        sq.add(line);
                        while ((line = brpepxml.readLine()) != null) {
                            sq.add(line);
                            if (line.trim().equals("</spectrum_query>")) {
                                out.write(handle_spectrum_query(sq, pinSpectrumRankNttNmc, pinSpectrumRankPepScore, is_DIA, rank));
                                break;
                            }
                        }
                    }
                }
                out.write("</msms_run_summary>\n" +
                        "</msms_pipeline_analysis>");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static SearchEngine findSearchEngine(List<StoxParserPepxml.NameValue> nameValues) {
        for (StoxParserPepxml.NameValue nv : nameValues) {
            if (nv.k.toLowerCase(Locale.ROOT).contains("msfragger"))
                return SearchEngine.MsFragger;
            if (nv.k.toLowerCase(Locale.ROOT).contains("comet"))
                return SearchEngine.Comet;
        }
        exit("Could not determine search engine from pepXML file header");
        return SearchEngine.Unknown; // should never get here, but static analysis complains
    }

    private static void parsePercolatorTsvAsRankPepScore(Path percolatorTargetPsms, Path percolatorDecoyPsms, double minProb, int max_rank, Map<String, PepScore[]> pinSpectrumRankPepScore) {
        for (final Path tsv : new Path[]{percolatorTargetPsms, percolatorDecoyPsms}) {
            try (final BufferedReader brtsv = Files.newBufferedReader(tsv)) {
                final String percolator_header = brtsv.readLine();
                final List<String> colnames = Arrays.asList(percolator_header.split("\t"));
                final int indexOfPSMId = colnames.indexOf("PSMId");
                final int indexOfPEP = colnames.indexOf("posterior_error_prob");
                final int indexOfScore = colnames.indexOf("score");
                String line;
                int countSkipped = 0;
                while ((line = brtsv.readLine()) != null) {
                    final String[] split = line.split("\t");
                    final String raw_psmid = split[indexOfPSMId];
                    final Spectrum_rank spectrum_rank = get_spectrum_rank_msfragger(raw_psmid);
                    final String specId = spectrum_rank.spectrum;
                    final int rank = spectrum_rank.rank;
                    final double pep = Double.parseDouble(split[indexOfPEP]);

                    if (1 - pep < minProb) {
                        countSkipped += 1;
                        continue;
                    }

                    final double score = Double.parseDouble(split[indexOfScore]);
                    pinSpectrumRankPepScore.computeIfAbsent(specId, e -> new PepScore[max_rank])[rank - 1] = new PepScore(pep, score);
                }
                System.out.printf("Skipped %d rows due to min probability cutoff %.4f in: %s\n", countSkipped, minProb, tsv);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void parsePinAsRankNttNmc(Path pin, int max_rank, final Map<String, NttNmc[]> pinSpectrumRankNttNmc, SearchEngine se) {
        try (final BufferedReader brtsv = Files.newBufferedReader(pin)) {
            final String pin_header = brtsv.readLine();
            final List<String> colnames = Arrays.asList(pin_header.split("\t"));
            final int indexOf_SpecId = colnames.indexOf("SpecId");

            final int indexOf_ntt = colnames.indexOf("ntt");
            final int indexOf_nmc = colnames.indexOf("nmc");
            Consumer3<String[], String, Integer> extractNttNmc = null;
            if (indexOf_ntt >= 0 && indexOf_nmc >= 0) {
                System.out.println("Looks to be MsFragger-compatible pin file");
                extractNttNmc = (split, specId, rank) -> {
                    final int ntt = Integer.parseInt(split[indexOf_ntt]);
                    final int nmc = Integer.parseInt(split[indexOf_nmc]);
                    pinSpectrumRankNttNmc.computeIfAbsent(specId, e -> new NttNmc[max_rank])[rank - 1] = new NttNmc(ntt, nmc);
                };
            }
            final int indexOf_enzN = colnames.indexOf("enzN");
            final int indexOf_enzC = colnames.indexOf("enzC");
            final int indexOf_enzInt = colnames.indexOf("enzInt");
            if (indexOf_enzC >= 0 && indexOf_enzN >= 0 && indexOf_enzInt >= 0) {
                System.out.println("Looks to be Comet-compatible pin file");
                extractNttNmc = (split, specId, rank) -> {
                    final int enzN = Integer.parseInt(split[indexOf_enzN]);
                    final int enzC = Integer.parseInt(split[indexOf_enzC]);
                    final int nmc = Integer.parseInt(split[indexOf_enzInt]);
                    final int ntt = enzN + enzC;
                    pinSpectrumRankNttNmc.computeIfAbsent(specId, e -> new NttNmc[max_rank])[rank - 1] = new NttNmc(ntt, nmc);
                };
            }
            if (extractNttNmc == null) {
                throw new IllegalStateException("Did not find ntt/nmc or enzN/enzC/enzInt columns in the pin file");
            }

            final Function<String, Spectrum_rank> extractSpecRank;
            switch (se) {
                case MsFragger:
                    extractSpecRank = PercolatorOutputToPepXML::get_spectrum_rank_msfragger;
                    break;
                case Comet:
                    extractSpecRank = PercolatorOutputToPepXML::get_spectrum_rank_comet;
                    break;
                default:
                    throw new UnsupportedOperationException("Only know how to extract SpectrumRank for Comet and MsFragger");
            }

            String line;
            while ((line = brtsv.readLine()) != null) {
                final String[] split = line.split("\t");
                final String raw_SpecId = split[indexOf_SpecId];
                final Spectrum_rank spectrum_rank = extractSpecRank.apply(raw_SpecId);
                final String specId = spectrum_rank.spectrum;
                final int rank = spectrum_rank.rank;
                extractNttNmc.accept(split, specId, rank);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static class NttNmc {

        final int ntt;
        final int nmc;

        public NttNmc(int ntt, int nmc) {
            this.ntt = ntt;
            this.nmc = nmc;
        }
    }


    static class PepScore {

        final double pep;
        final double score;

        public PepScore(double pep, double score) {
            this.pep = pep;
            this.score = score;
        }
    }
}
