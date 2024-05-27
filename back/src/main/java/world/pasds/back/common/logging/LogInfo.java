package world.pasds.back.common.logging;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Map;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogInfo {
    private String url;
    private String name;
    private String method;
    private Long ResponseTime;
    private String exception;
    private String className;
    private Map<String, String> headers;
}
