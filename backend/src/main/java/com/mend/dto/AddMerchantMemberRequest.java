package com.mend.dto;

public class AddMerchantMemberRequest {

    private String email;
    private String password;
    private String displayName;
    private String roleName;

    public AddMerchantMemberRequest() {
    }

    public AddMerchantMemberRequest(String email, String password, String displayName, String roleName) {
        this.email = email;
        this.password = password;
        this.displayName = displayName;
        this.roleName = roleName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }
}
