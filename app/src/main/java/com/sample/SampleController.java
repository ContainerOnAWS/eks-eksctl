package com.sample;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMethod;

@RestController
public class SampleController {

    @RequestMapping(value="/", method=RequestMethod.GET)
    public String home() {
        return "OK";
    }

    @RequestMapping(value="/{seviceid}/{pathvar1}", method=RequestMethod.GET)
    public String pingPath2() {
        return "OK";
    }

    @RequestMapping(value="/{seviceid}/{pathvar1}/{pathvar2}", method=RequestMethod.GET)
    public String pingPath3() {
        return "OK";
    }

    @RequestMapping(value="/{seviceid}/{pathvar1}/{pathvar2}/{pathvar3}", method=RequestMethod.GET)
    public String pingPath4() {
        return "OK";
    }
}
