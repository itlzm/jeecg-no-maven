package org.jeecgframework.core.interceptors;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.alibaba.fastjson.JSONObject;
import org.apache.log4j.Logger;
import org.jeecgframework.core.annotation.JAuth;
import org.jeecgframework.core.common.exception.GlobalExceptionResolver;
import org.jeecgframework.core.common.model.json.AjaxJson;
import org.jeecgframework.core.constant.Globals;
import org.jeecgframework.core.enums.Permission;
import org.jeecgframework.core.extend.hqlsearch.SysContextSqlConvert;
import org.jeecgframework.core.util.*;
import org.jeecgframework.web.system.manager.ClientManager;
import org.jeecgframework.web.system.pojo.base.Client;
import org.jeecgframework.web.system.pojo.base.TSDataRule;
import org.jeecgframework.web.system.pojo.base.TSFunction;
import org.jeecgframework.web.system.pojo.base.TSOperation;
import org.jeecgframework.web.system.pojo.base.TSUser;
import org.jeecgframework.web.system.service.SystemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;


/**
 * 权限拦截器
 * 
 * @author  张代浩
 * 
 */
public class AuthInterceptor implements HandlerInterceptor {
	 
	private static final Logger logger = Logger.getLogger(AuthInterceptor.class);
	private SystemService systemService;
	private List<String> excludeUrls;
	/**
	 * 包含匹配（请求链接包含该配置链接，就进行过滤处理）
	 */
	private List<String> excludeContainUrls;

	public List<String> getExcludeUrls() {
		return excludeUrls;
	}

	public void setExcludeUrls(List<String> excludeUrls) {
		this.excludeUrls = excludeUrls;
	}

	public List<String> getExcludeContainUrls() {
		return excludeContainUrls;
	}

	public void setExcludeContainUrls(List<String> excludeContainUrls) {
		this.excludeContainUrls = excludeContainUrls;
	}

	public SystemService getSystemService() {
		return systemService;
	}

	@Autowired
	public void setSystemService(SystemService systemService) {
		this.systemService = systemService;
	}

	/**
	 * 在controller后拦截
	 */
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object object, Exception exception) throws Exception {
	}

	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object object, ModelAndView modelAndView) throws Exception {

	}

	/**
	 * 在controller前拦截
	 */
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object object) throws Exception {

		//判断是否被注解跳过权限认证  先判断类注解然后方法注解 都没有则走原来逻辑
		HandlerMethod handlerMethod=(HandlerMethod)object;
		JAuth jauthType =handlerMethod.getBean().getClass().getAnnotation(JAuth.class);
		if(jauthType!=null){
			if(jauthType.auth()==Permission.SKIP_AUTH){
				return true;
			}
		}else{
			//JAuthority jauthMethod =handlerMethod.getMethodAnnotation(JAuthority.class);
			JAuth jauthMethod =handlerMethod.getMethod().getAnnotation(JAuth.class);
			if(jauthMethod!=null){
				Permission permission=jauthMethod.auth();
				if(permission==Permission.SKIP_AUTH){
					return true;
				}
			}
		}

		Boolean isAjax=isAjax(request,response);

		String requestPath = ResourceUtil.getRequestPath(request);// 用户访问的资源地址
		//logger.info("-----authInterceptor----requestPath------"+requestPath);
		//步骤一： 判断是否是排除拦截请求，直接返回TRUE

		if (requestPath.length()>3&&"api/".equals(requestPath.substring(0,4))) {
			return true;
		}

		if (excludeUrls.contains(requestPath)) {
			return true;

		} else if(moHuContain(excludeContainUrls, requestPath)){
			return true;

		} else {
			//步骤二： 权限控制，优先重组请求URL(考虑online请求前缀一致问题)
			String clickFunctionId = request.getParameter("clickFunctionId");
			Client client = ClientManager.getInstance().getClient(ContextHolderUtils.getSession().getId());
			TSUser currLoginUser = client!=null?client.getUser():null;
			if (client != null && currLoginUser!=null ) {
				//onlinecoding的访问地址有规律可循，数据权限链接篡改
				if(requestPath.equals("cgAutoListController.do?datagrid")) {
					requestPath += "&configId=" +  request.getParameter("configId");
				}
				if(requestPath.equals("cgAutoListController.do?list")) {
					requestPath += "&id=" +  request.getParameter("id");
				}

				if(requestPath.endsWith("?olstylecode=")) {
					requestPath = requestPath.replace("?olstylecode=", "");
				}
				
				//步骤三：  根据重组请求URL,进行权限授权判断
				if((!hasMenuAuth(requestPath,clickFunctionId,currLoginUser)) && !currLoginUser.getUserName().equals("admin")){

					if(isAjax){
							processAjax(response);
					}else {
						response.sendRedirect(request.getSession().getServletContext().getContextPath()+"/loginController.do?noAuth");
					}

					return false;
				} 

				
				//解决rest风格下 权限失效问题
				String functionId="";
				String uri= request.getRequestURI().substring(request.getContextPath().length() + 1);
				String realRequestPath = null;
				if(uri.endsWith(".do")||uri.endsWith(".action")){
					realRequestPath=requestPath;
				}else {
					realRequestPath=uri;
				}

//				if(!oConvertUtils.isEmpty(clickFunctionId)){
//					functionId = clickFunctionId;
//				}else{

					if(realRequestPath.indexOf("autoFormController/af/")>-1 && realRequestPath.indexOf("?")!=-1){
						realRequestPath = realRequestPath.substring(0, realRequestPath.indexOf("?"));
					}

					List<TSFunction> functions = systemService.findByProperty(TSFunction.class, "functionUrl", realRequestPath);
					if (functions.size()>0){
						functionId = functions.get(0).getId();
					}
//				}

				//Step.1 第一部分处理页面表单和列表的页面控件权限（页面表单字段+页面按钮等控件）
				if(!oConvertUtils.isEmpty(functionId)){

					if(!currLoginUser.getUserName().equals("admin")){
						//获取菜单对应的页面控制权限（包括表单字段和操作按钮）

						List<TSOperation> operations = systemService.getOperationsByUserIdAndFunctionId(currLoginUser.getId(), functionId);
						request.setAttribute(Globals.NOAUTO_OPERATIONCODES, operations);
						if(operations==null){
							request.setAttribute(Globals.OPERATIONCODES, null);
						}else{
							Set<String> operationCodes = new HashSet<String>();
							for (TSOperation operation : operations) {
								operationCodes.add(operation.getId());
							}
							request.setAttribute(Globals.OPERATIONCODES, operationCodes);
						}
					}

					
					//Set<String> operationCodes = systemService.getOperationCodesByUserIdAndFunctionId(currLoginUser.getId(), functionId);
					//request.setAttribute(Globals.OPERATIONCODES, operationCodes);
				//}
				//if(!oConvertUtils.isEmpty(functionId)){

					
//					List<TSOperation> allOperation=this.systemService.findByProperty(TSOperation.class, "TSFunction.id", functionId);
//					List<TSOperation> newall = new ArrayList<TSOperation>();
//					if(allOperation.size()>0){
//						for(TSOperation s:allOperation){ 
//						    //s=s.replaceAll(" ", "");
//							newall.add(s); 
//						}

//						String hasOperSql="SELECT operation FROM t_s_role_function fun, t_s_role_user role WHERE  " +
//							"fun.functionid='"+functionId+"' AND fun.operation is not null  AND fun.roleid=role.roleid AND role.userid='"+currLoginUser.getId()+"' ";

//						List<String> hasOperList = this.systemService.findListbySql(hasOperSql); 
//					    for(String operationIds:hasOperList){
//							    for(String operationId:operationIds.split(",")){
//							    	operationId=operationId.replaceAll(" ", "");
//							        TSOperation operation =  new TSOperation();
//							        operation.setId(operationId);
//							    	newall.remove(operation);
//							    } 
//						} 
//					}
					/*List<TSOperation> newall = new ArrayList<TSOperation>();
					String hasOperSql="SELECT operation FROM t_s_role_function fun, t_s_role_user role WHERE  " +
										"fun.functionid='"+functionId+"' AND fun.operation is not null  AND fun.roleid=role.roleid AND role.userid='"+currLoginUser.getId()+"' ";
					List<String> hasOperList = this.systemService.findListbySql(hasOperSql); 
				    for(String operationIds:hasOperList){
						    for(String operationId:operationIds.split(",")){
						    	operationId=operationId.replaceAll(" ", "");
						        TSOperation operation =  systemService.get(TSOperation.class, operationId);
						        if(operation!=null && operation.getOperationcode()!=null &&
						        		(operation.getOperationcode().startsWith("#")|| operation.getOperationcode().startsWith("."))){
						        	newall.add(operation);
						        }
						    } 
					} 
					request.setAttribute(Globals.NOAUTO_OPERATIONCODES, newall);*/

					
					 //Step.2  第二部分处理列表数据级权限 (菜单数据规则集合)
					 List<TSDataRule> MENU_DATA_AUTHOR_RULES = new ArrayList<TSDataRule>(); 
					 String MENU_DATA_AUTHOR_RULE_SQL="";

					
				 	//数据权限规则的查询
				 	//查询所有的当前这个用户所对应的角色和菜单的datarule的数据规则id

					 if(!currLoginUser.getUserName().equals("admin")){
						 //Globals.BUTTON_AUTHORITY_CHECK
						 Set<String> dataruleCodes = systemService.getOperationCodesByUserIdAndDataId(currLoginUser.getId(), functionId);
						 request.setAttribute("dataRulecodes", dataruleCodes);
						 for (String dataRuleId : dataruleCodes) {
							TSDataRule dataRule = systemService.getEntity(TSDataRule.class, dataRuleId);
						 	MENU_DATA_AUTHOR_RULES.add(dataRule);
						 	MENU_DATA_AUTHOR_RULE_SQL += SysContextSqlConvert.setSqlModel(dataRule);
						}
					 }

					 JeecgDataAutorUtils.installDataSearchConditon(request, MENU_DATA_AUTHOR_RULES);//菜单数据规则集合
					 JeecgDataAutorUtils.installDataSearchConditon(request, MENU_DATA_AUTHOR_RULE_SQL);//菜单数据规则sql

				}
				return true;
			} else {
				//forword(request);
				forward(request, response);
				return false;
			}

		}
	}

	/**
	 * 判断用户是否有菜单访问权限
	 * @param requestPath
	 * @param clickFunctionId
	 * @param currLoginUser
	 * @return
	 */
	private boolean hasMenuAuth(String requestPath,String clickFunctionId,TSUser currLoginUser){
        String userid = currLoginUser.getId();

        //step.1 先判断请求是否配置菜单，没有配置菜单默认不作权限控制[注意：这里不限制权限类型菜单]
        String hasMenuSql = "select count(*) from t_s_function where functiontype = 0 and functionurl = '"+requestPath+"'";
        Long hasMenuCount = systemService.getCountForJdbc(hasMenuSql);
        if(hasMenuCount<=0){
        	return true;
        }
        
        //step.2 判断菜单是否有角色权限
        Long authSize = Long.valueOf(0);
		String sql = "SELECT count(*) FROM t_s_function f,t_s_role_function  rf,t_s_role_user ru " +
					" WHERE f.id=rf.functionid AND rf.roleid=ru.roleid AND " +
					"ru.userid='"+userid+"' AND f.functionurl = '"+requestPath+"'";
		authSize = this.systemService.getCountForJdbc(sql);
		if(authSize <=0){
			//step.3 判断菜单是否有组织机构角色权限
            String orgId = currLoginUser.getCurrentDepart().getId();
            Long orgAuthSize = Long.valueOf(0);
            String functionOfOrgSql = "SELECT count(*) from t_s_function f, t_s_role_function rf, t_s_role_org ro  " +
                    "WHERE f.ID=rf.functionid AND rf.roleid=ro.role_id " +
                    "AND ro.org_id='" +orgId+ "' AND f.functionurl = '"+requestPath+"'";
            orgAuthSize = this.systemService.getCountForJdbc(functionOfOrgSql);
			return orgAuthSize > 0;
        }else{
			return true;
		}

	}

	
	/**
	 * 转发
	 * 
	 * @param user
	 * @param req
	 * @return
	 */
	@RequestMapping(params = "forword")
	public ModelAndView forword(HttpServletRequest request) {
		return new ModelAndView(new RedirectView("loginController.do?login"));
	}

	private void forward(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		//超时，未登陆页面跳转
		//response.sendRedirect(request.getServletContext().getContextPath()+"/loginController.do?login");

		response.sendRedirect(request.getSession().getServletContext().getContextPath()+"/webpage/login/timeout.jsp");

		//request.getRequestDispatcher("loginController.do?login").forward(request, response);

	}
	
	/**
	 * 模糊匹配字符串
	 * @param list
	 * @param key
	 * @return
	 */
	private boolean moHuContain(List<String> list,String key){
		for(String str : list){
			if(key.contains(str)){
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断当前请求是否为异步请求.
	 */
	@SuppressWarnings("unused")
	private boolean isAjax(HttpServletRequest request, HttpServletResponse response){
		return oConvertUtils.isNotEmpty(request.getHeader("X-Requested-With"));
	}

	private void processAjax(HttpServletResponse response){
		AjaxJson json = new AjaxJson();
		json.setSuccess(false);
		json.setMsg("用户权限不足，请联系管理员!");
		PrintWriter pw = null;
		try {
			pw=response.getWriter();
			pw.write(JSONHelper.bean2json(json));
			pw.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}finally{
			pw.close();
		}
	}

}
