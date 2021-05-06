package com.fpt.edu.schedule.controller;

import com.fpt.edu.schedule.model.Request;
import com.fpt.edu.schedule.repository.base.QueryParam;
import com.fpt.edu.schedule.service.base.RequestService;
import lombok.AllArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;


@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/requests")
public class RequestController {
    RequestService requestService;


    @PostMapping("/generate")
    public ResponseEntity generateFile(@RequestParam("file") MultipartFile multipartFile,
                                       @RequestParam(name = "semesterId") int semesterId,
                                       @RequestHeader("GoogleId") String hodGoogleId) {
            requestService.generateExcelFile(multipartFile,semesterId,hodGoogleId);
            return new ResponseEntity(HttpStatus.OK);
    }

    @PostMapping("/generateAdditional")
    public ResponseEntity generateAdditionalFile(@RequestParam("file") MultipartFile multipartFile,
                                       @RequestParam(name = "semesterId") int semesterId,
                                       @RequestHeader("GoogleId") String hodGoogleId) {
        requestService.generateAdditionalExcelFile(multipartFile,semesterId,hodGoogleId);
        return new ResponseEntity(HttpStatus.OK);
    }

    @PostMapping("/export")
    public ResponseEntity exportExcel(@RequestParam(name = "semesterId") int semesterId,
                                      @RequestParam(name = "groupBy") String groupBy) {
        ByteArrayInputStream in = requestService.exportFile(semesterId,groupBy);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=timetable.xlsx");

        return ResponseEntity
                .ok()
                .headers(headers)
                .body(new InputStreamResource(in));

    }
    @PostMapping("/filter")
    public ResponseEntity<Request> getRequestByCriteria(@RequestBody QueryParam queryParam) {

            return new ResponseEntity(requestService.findByCriteria(queryParam), HttpStatus.OK);
    }
    @PostMapping
    public ResponseEntity<Request> addRequest(@RequestBody Request request,
                                             @RequestHeader("GoogleId") String currentLecturerId) {
            return new ResponseEntity(requestService.addRequest(request,currentLecturerId), HttpStatus.OK);

    }
    @PutMapping
    public ResponseEntity<Request> updateRequest(@RequestBody Request request,
                                                 @RequestHeader("GoogleId")String lecturerId) {
            return new ResponseEntity(requestService.updateRequest(request,lecturerId), HttpStatus.OK);

    }
    @DeleteMapping("/{reportId}")
    public ResponseEntity remove(@PathVariable("reportId") int expectedId) {
            requestService.removeRequestById(expectedId);
            return new ResponseEntity( HttpStatus.OK);

    }

//    @GetMapping("/sendMail")
//    public String sendMail(@RequestParam("lecturerId") String lecturerId) {
//        SimpleMailMessage msg = new SimpleMailMessage();
//        try {
//
//            msg.setSubject("Logout");
//            msg.setText("Ban vua logout");
//
//        }catch (Exception e){
//            System.out.println(e.getMessage());
//        }
//        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
//        if (auth != null) {
//
//        }
//        return "redirect:/login";
//    }

}
