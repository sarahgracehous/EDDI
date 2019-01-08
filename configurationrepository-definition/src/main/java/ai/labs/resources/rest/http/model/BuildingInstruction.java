package ai.labs.resources.rest.http.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BuildingInstruction {
    private String pathToTargetArray;
    private String iterationObjectName = "obj";
    private String templateFilterExpression = "";
}
