package energosbyt.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

public final class OwnerPatch {
    private String fio;
    private String phone;

    @JsonSetter(value = "fio", nulls = Nulls.FAIL)
    public void setFio(String fio) {
        this.fio = fio;
    }

    @JsonSetter(value = "phone", nulls = Nulls.FAIL)
    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String fio() {
        return fio;
    }

    public String phone() {
        return phone;
    }
}
