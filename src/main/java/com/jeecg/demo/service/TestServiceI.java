package com.jeecg.demo.service;
import com.jeecg.demo.entity.TestEntity;
import org.jeecgframework.core.common.service.CommonService;

import java.io.Serializable;

public interface TestServiceI extends CommonService{
	
 	public void delete(TestEntity entity) throws Exception;
 	
 	public Serializable save(TestEntity entity) throws Exception;
 	
 	public void saveOrUpdate(TestEntity entity) throws Exception;
 	
}
