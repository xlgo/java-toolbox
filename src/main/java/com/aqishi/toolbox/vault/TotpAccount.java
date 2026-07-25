package com.aqishi.toolbox.vault;

/** TOTP record and its per-account display preferences. */
public class TotpAccount {
    private String id;
    private String label;
    private String secret;
    private String issuer = "";
    private String algorithm = "SHA1";
    private int digits = 6;
    private int period = 30;
    private boolean showDirectly = true;

    public TotpAccount() {
    }

    public TotpAccount(String id, String label, String secret, String issuer,
                       String algorithm, int digits, int period, boolean showDirectly) {
        setId(id);
        setLabel(label);
        setSecret(secret);
        setIssuer(issuer);
        setAlgorithm(algorithm);
        setDigits(digits);
        setPeriod(period);
        setShowDirectly(showDirectly);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer == null ? "" : issuer;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public int getDigits() {
        return digits;
    }

    public void setDigits(int digits) {
        this.digits = digits;
    }

    public int getPeriod() {
        return period;
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public boolean isShowDirectly() {
        return showDirectly;
    }

    public void setShowDirectly(boolean showDirectly) {
        this.showDirectly = showDirectly;
    }

    public TotpAccount copy() {
        return new TotpAccount(id, label, secret, issuer, algorithm,
                digits, period, showDirectly);
    }
}
