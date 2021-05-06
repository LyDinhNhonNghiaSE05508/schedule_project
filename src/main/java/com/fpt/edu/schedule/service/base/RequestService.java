package com.fpt.edu.schedule.service.base;

import com.fpt.edu.schedule.model.Request;
import com.fpt.edu.schedule.repository.base.QueryParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;

public interface RequestService {
    void generateExcelFile(MultipartFile multipartFile, int semesterId,String hodGoogleId);

    void generateAdditionalExcelFile(MultipartFile multipartFile, int semesterId,String hodGoogleId);

    Request addRequest(Request request, String lecturerId);

    Request updateRequest(Request request,String lecturerId);

    void removeRequestById(int id);

    QueryParam.PagedResultSet<Request> findByCriteria(QueryParam queryParam);

    ByteArrayInputStream exportFile(int semesterId,String groupBy);

}
