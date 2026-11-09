package energosbyt.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import energosbyt.dto.Installation;
import energosbyt.dto.Invoices;
import energosbyt.dto.Owner;
import energosbyt.dto.OwnerPatch;
import energosbyt.dto.Reading;
import energosbyt.dto.Readings;
import energosbyt.dto.Realty;
import energosbyt.dto.Tariff;
import energosbyt.service.EnergosbytService;

@RestController
public class EnergosbytController {
    private final EnergosbytService service;

    public EnergosbytController(EnergosbytService service) {
        this.service = service;
    }

    @GetMapping("/ping")
    public void ping() {}

    @GetMapping("/users/{id}")
    public Owner owner(@PathVariable("id") String id) {
        return service.owner(id);
    }

    @PatchMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void patchOwner(@PathVariable("id") String id, @RequestBody OwnerPatch patch) {
        service.patchOwner(id, patch);
    }

    @GetMapping("/realty/{id}")
    public Realty realty(@PathVariable("id") String id) {
        return service.realty(id);
    }

    @PostMapping("/realty/{id}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public void postReading(@PathVariable("id") String id, @RequestBody Reading reading) {
        service.postReading(id, reading);
    }

    @GetMapping("/realty/{id}/readings")
    public Readings readings(@PathVariable("id") String id,
                      @RequestParam(name = "date_from", required = false) String from,
                      @RequestParam(name = "date_to", required = false) String to) {
        return service.readings(id, from, to);
    }

    @GetMapping("/realty/{id}/invoices")
    public Invoices invoices(@PathVariable("id") String id,
                      @RequestParam(name = "limit", required = false) String limit,
                      @RequestParam(name = "offset", required = false) String offset) {
        return service.invoices(id, limit, offset);
    }

    @GetMapping("/tariffs")
    public Tariff tariff(@RequestParam(name = "date", required = false) String date) {
        return service.tariff(date);
    }

    @PostMapping("/realty/{id}/gauges")
    @ResponseStatus(HttpStatus.CREATED)
    public void installGauge(@PathVariable("id") String id, @RequestBody Installation body) {
        service.installGauge(id, body);
    }
}
