package com.autoinvoice.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolStatusDto {
    private String toolName;
    private boolean connected;
}
