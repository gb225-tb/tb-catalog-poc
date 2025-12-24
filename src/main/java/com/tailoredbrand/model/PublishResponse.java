package com.tailoredbrand.model;

import lombok.AllArgsConstructor;
import lombok.Data;


@Data
@AllArgsConstructor
public class PublishResponse {
    private String status;
    private String messageId;
}

