package com.mend.dto;

public class BootstrapRequest {

    private String merchantName;
    private String adminEmail;
    private String adminPassword;
    private String adminDisplayName;

    public BootstrapRequest() {
    }

    public BootstrapRequest(String merchantName, String adminEmail, String adminPassword, String adminDisplayName) {
        this.merchantName = merchantName;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminDisplayName = adminDisplayName;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public String getAdminDisplayName() {
        return adminDisplayName;
    }

    public void setAdminDisplayName(String adminDisplayName) {
        this.adminDisplayName = adminDisplayName;
    }
}
