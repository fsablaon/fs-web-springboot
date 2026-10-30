package org.fsweb.stocks.controller;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.fsweb.stocks.dto.Stock;
import org.fsweb.stocks.exception.StockNotFoundException;
import org.fsweb.stocks.service.StockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @GetMapping
    public List<Stock> all() {
        return stockService.findAll();
    }

    @GetMapping("/{symbol}")
    public Stock bySymbol(@PathVariable String symbol) {
        return stockService.findBySymbol(symbol)
                .orElseThrow(() -> new StockNotFoundException(symbol));
    }
}
