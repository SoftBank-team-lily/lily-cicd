package com.lily.cicd.schema;

/** 사용자 DB 에서 스키마 작업(dry-run, migrate, 되돌리기, 백업)이 실패했다. 메시지에 비밀번호를 넣지 않는다 */
public class SchemaOperationException extends RuntimeException {

    public SchemaOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
