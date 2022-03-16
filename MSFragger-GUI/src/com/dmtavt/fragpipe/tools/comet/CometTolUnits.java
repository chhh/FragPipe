package com.dmtavt.fragpipe.tools.comet;


public enum CometTolUnits {
    PPM (2),
    amu(0),
    mmu(1);

    private final int val;

    private CometTolUnits(int val) {
        this.val = val;
    }

    public int valueInParamsFile() {
        return val;
    }

    public static com.dmtavt.fragpipe.tools.enums.PrecursorMassTolUnits fromParamsFileToUi(String fileRepresentation) {
        int v = Integer.parseInt(fileRepresentation);
        for (int i = 0; i < com.dmtavt.fragpipe.tools.enums.PrecursorMassTolUnits.values().length; i++) {
            com.dmtavt.fragpipe.tools.enums.PrecursorMassTolUnits u = com.dmtavt.fragpipe.tools.enums.PrecursorMassTolUnits.values()[i];
            if (u.valueInParamsFile() == v)
                return u;
        }
        throw new IllegalStateException("Value for MassTolUnits stored in params file for property " + CometParams.PROP_peptide_mass_units +
                " does not correspond to enum values of MassTolUnits.");
    }
}