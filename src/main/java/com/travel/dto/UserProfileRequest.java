package com.travel.dto;

import lombok.Data;

@Data
public class UserProfileRequest {
    private String username;
    private String email;
    private String phone;
    private String avatar;
}
