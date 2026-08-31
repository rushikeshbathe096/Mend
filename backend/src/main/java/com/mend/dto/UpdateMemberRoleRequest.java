package com.mend.dto;

public class UpdateMemberRoleRequest {

    private String roleName;

    public UpdateMemberRoleRequest() {
    }

    public UpdateMemberRoleRequest(String roleName) {
        this.roleName = roleName;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }
}
